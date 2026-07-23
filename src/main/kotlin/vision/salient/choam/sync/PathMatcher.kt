package vision.salient.choam.sync

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher as NioPathMatcher

class PathMatcher(patterns: List<String>) {
    private val matchers: List<NioPathMatcher> = patterns.map { pattern ->
        val glob = if (pattern.startsWith("**/")) {
            "glob:**/$pattern"
        } else if (pattern.startsWith("*.")) {
            "glob:**/$pattern"
        } else {
            "glob:$pattern"
        }
        FileSystems.getDefault().getPathMatcher(glob)
    }

    fun matches(path: Path): Boolean {
        return matchers.any { matcher ->
            matcher.matches(path) || matcher.matches(path.fileName)
        }
    }

    fun matches(pathString: String): Boolean {
        val path = java.nio.file.Paths.get(pathString)
        return matches(path)
    }
}
