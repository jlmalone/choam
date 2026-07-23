package vision.salient.choam

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import vision.salient.choam.cli.CatalogAllCommand
import vision.salient.choam.cli.CatalogMergeCommand
import vision.salient.choam.cli.CatalogPurgeCommand
import vision.salient.choam.cli.CatalogSyncCommand
import vision.salient.choam.cli.CatalogUpdateCommand
import vision.salient.choam.cli.ConfigCommand
import vision.salient.choam.cli.DbSyncCommand
import vision.salient.choam.cli.DbTransferCommand
import vision.salient.choam.cli.DbImportCommand
import vision.salient.choam.cli.DiffCommand
import vision.salient.choam.cli.HistoryCommand
import vision.salient.choam.cli.InitCommand
import vision.salient.choam.cli.ManifestCommand
import vision.salient.choam.cli.ManifestLifecycleCommand
import vision.salient.choam.cli.FederationSummaryCommand
import vision.salient.choam.cli.GlobalSearchCommand
import vision.salient.choam.cli.InspectCommand
import vision.salient.choam.cli.LockCommand
import vision.salient.choam.cli.MoveCommand
import vision.salient.choam.cli.PullCommand
import vision.salient.choam.cli.PushCommand
import vision.salient.choam.cli.QueueCommand
import vision.salient.choam.cli.RegisterCommand
import vision.salient.choam.cli.SendCommand
import vision.salient.choam.cli.RebuildIndexCommand
import vision.salient.choam.cli.StatusCommand
import vision.salient.choam.cli.SyncCommand
import vision.salient.choam.cli.VerifyCommand
import vision.salient.choam.cli.drivesCommand
import vision.salient.choam.cli.indexCommand
import vision.salient.choam.cli.FulfillCommand
import vision.salient.choam.cli.PlanCommand
import vision.salient.choam.cli.RequestCopyCommand
import vision.salient.choam.cli.ReportCommand
import vision.salient.choam.cli.ServeCommand
import vision.salient.choam.cli.backupCommand
import vision.salient.choam.cli.daemonCommand
import vision.salient.choam.cli.dagCommand
import vision.salient.choam.cli.gossipCommand
import vision.salient.choam.cli.houseCommand
import vision.salient.choam.cli.junkCommand
import vision.salient.choam.cli.shareCommand

class ChoamCli : CliktCommand(name = "choam", help = "Cross-Host Orchestrated Asset Management") {
    override fun run() {
        // Root command does nothing on its own; subcommands handle behavior.
    }
}

fun main(args: Array<String>) =
    ChoamCli()
        .subcommands(
            SyncCommand(),
            PushCommand(),
            PullCommand(),
            SendCommand(),
            QueueCommand(),
            StatusCommand(),
            HistoryCommand(),
            ConfigCommand(),
            ManifestCommand(),
            ManifestLifecycleCommand(),
            InitCommand(),
            CatalogAllCommand(),
            CatalogMergeCommand(),
            CatalogPurgeCommand(),
            CatalogSyncCommand(),
            CatalogUpdateCommand(),
            RebuildIndexCommand(),
            GlobalSearchCommand(),
            InspectCommand(),
            FederationSummaryCommand(),
            DiffCommand(),
            MoveCommand(),
            VerifyCommand(),
            PlanCommand(),
            RequestCopyCommand(),
            FulfillCommand(),
            ReportCommand(),
            ServeCommand(),
            drivesCommand(),
            indexCommand(),
            junkCommand(),
            houseCommand(),
            shareCommand(),
            backupCommand(),
            gossipCommand(),
            dagCommand(),
            daemonCommand(),
            DbSyncCommand(),
            DbTransferCommand(),
            DbImportCommand(),
            RegisterCommand(),
            LockCommand()
        )
        .main(args)

