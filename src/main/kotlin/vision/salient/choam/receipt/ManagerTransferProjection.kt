package vision.salient.choam.receipt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TRANSFER_RECEIPT_ENVELOPE_SCHEMA_V1 = "choam.transfer-receipts.v1"

@Serializable data class ManagerTransferView(val transferId: String, val attemptId: String? = null, val queueEntryId: String? = null, val state: TransferReceiptState, val deliveryCompleted: Boolean, val failureCode: String? = null, val requiresDestinationEvidence: Boolean = !deliveryCompleted)
@Serializable enum class ReceiptDecodeRejectionCode { MALFORMED_JSON, ROOT_NOT_OBJECT_OR_ARRAY, UNSUPPORTED_ENVELOPE_SCHEMA, MIXED_ENVELOPE, MALFORMED_QUEUE, MALFORMED_RECEIPT, UNSUPPORTED_CHILD_SCHEMA, UNKNOWN_LEGACY_STATE }
@Serializable data class ReceiptDecodeRejection(val entryIndex: Int? = null, val code: ReceiptDecodeRejectionCode)
@Serializable data class DecodeResult(val views: List<ManagerTransferView>, val rejections: List<ReceiptDecodeRejection>)

/** Data-only Manager seam. Decoding does not trust proof fields and cannot assert delivery. */
object ManagerTransferProjection {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(payload: String, verifier: DestinationEvidenceVerifier? = null): DecodeResult = try {
        when (val root = json.parseToJsonElement(payload)) {
            is JsonArray -> decodeLegacyQueue(root)
            is JsonObject -> decodeObject(root, verifier)
            else -> DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.ROOT_NOT_OBJECT_OR_ARRAY)))
        }
    } catch (_: Exception) { DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.MALFORMED_JSON))) }

    fun project(receipt: TransferReceiptV1, verifier: DestinationEvidenceVerifier? = null): ManagerTransferView {
        val complete = receipt.state == TransferReceiptState.COMPLETED && TransferReceiptReducer.isAuthoritativelyComplete(receipt, receipt.destinationEvidence, verifier)
        return ManagerTransferView(receipt.transferId, receipt.attemptId, receipt.queueEntryId, if (complete) TransferReceiptState.COMPLETED else if (receipt.state == TransferReceiptState.COMPLETED) TransferReceiptState.VERIFYING_FILES else receipt.state, complete, receipt.failureCode)
    }

    private fun decodeObject(root: JsonObject, verifier: DestinationEvidenceVerifier?): DecodeResult {
        val hasQueue = root["queue"] != null
        val hasReceipts = root["transferReceipts"] != null
        if (hasQueue && hasReceipts) return DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.MIXED_ENVELOPE)))
        val schema = (root["schema"] as? JsonPrimitive)?.content
        return when {
            !hasQueue && !hasReceipts && schema == TRANSFER_RECEIPT_SCHEMA_V1 -> decodeReceipt(root, null, verifier)
            hasReceipts && schema == TRANSFER_RECEIPT_ENVELOPE_SCHEMA_V1 -> decodeReceiptEnvelope(root["transferReceipts"], verifier)
            hasQueue && (schema == null || schema == "1" || schema == "manager.queue.v1") -> decodeLegacyQueue(root["queue"])
            else -> DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.UNSUPPORTED_ENVELOPE_SCHEMA)))
        }
    }

    private fun decodeReceiptEnvelope(value: Any?, verifier: DestinationEvidenceVerifier?): DecodeResult {
        val array = value as? JsonArray ?: return DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.MALFORMED_RECEIPT)))
        val views = mutableListOf<ManagerTransferView>(); val rejections = mutableListOf<ReceiptDecodeRejection>()
        array.forEachIndexed { index, child ->
            val objectChild = child as? JsonObject
            if (objectChild == null || (objectChild["schema"] as? JsonPrimitive)?.content != TRANSFER_RECEIPT_SCHEMA_V1) rejections += ReceiptDecodeRejection(index, ReceiptDecodeRejectionCode.UNSUPPORTED_CHILD_SCHEMA)
            else decodeReceipt(objectChild, index, verifier).also { views += it.views; rejections += it.rejections }
        }
        return DecodeResult(views, rejections)
    }

    private fun decodeReceipt(value: JsonObject, index: Int?, verifier: DestinationEvidenceVerifier?): DecodeResult = try {
        DecodeResult(listOf(project(json.decodeFromJsonElement(TransferReceiptV1.serializer(), value), verifier)), emptyList())
    } catch (_: Exception) { DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(index, ReceiptDecodeRejectionCode.MALFORMED_RECEIPT))) }

    private fun decodeLegacyQueue(value: Any?): DecodeResult {
        val array = value as? JsonArray ?: return DecodeResult(emptyList(), listOf(ReceiptDecodeRejection(code = ReceiptDecodeRejectionCode.MALFORMED_QUEUE)))
        val views = mutableListOf<ManagerTransferView>(); val rejections = mutableListOf<ReceiptDecodeRejection>()
        array.forEachIndexed { index, child ->
            val objectChild = child as? JsonObject
            val id = objectChild?.string("id")
            val status = objectChild?.string("status")?.uppercase()
            val state = when (status) { "PENDING" -> TransferReceiptState.QUEUE_ADMITTED; "RUNNING" -> TransferReceiptState.ACTIVE; "COMPLETED" -> TransferReceiptState.VERIFYING_FILES; "FAILED" -> TransferReceiptState.FAILED; "CANCELLED" -> TransferReceiptState.CANCELLED; else -> null }
            if (id == null || state == null) rejections += ReceiptDecodeRejection(index, if (status == null || status !in setOf("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")) ReceiptDecodeRejectionCode.UNKNOWN_LEGACY_STATE else ReceiptDecodeRejectionCode.MALFORMED_QUEUE)
            else views += ManagerTransferView(id, queueEntryId = id, state = state, deliveryCompleted = false, failureCode = if (state == TransferReceiptState.FAILED) "LEGACY_FAILURE_DETAIL_REDACTED" else null)
        }
        return DecodeResult(views, rejections)
    }
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) }
}
