package cn.qaiu.nfd.android.termux

/**
 * Turns a folder picked with the system picker into a file path. Termux writes the files itself, so
 * it needs a real path rather than a content:// grant; only local storage providers have one.
 */
object StoragePaths {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    fun fromTreeDocument(authority: String?, documentId: String?, externalRoot: String): String? {
        if (authority == null || documentId.isNullOrBlank()) return null
        if (authority == DOWNLOADS) {
            return documentId.removePrefix("raw:").takeIf { it.startsWith("/") }
        }
        if (authority != EXTERNAL_STORAGE) return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/')
        val root = when (volume) {
            "primary" -> externalRoot.trimEnd('/')
            "home" -> externalRoot.trimEnd('/') + "/Documents"
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}
