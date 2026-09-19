package net.coffeebrewia.roastengine.modding;

/**
 * A mod as listed by the mod.io API.
 *
 * @param id            mod.io mod id
 * @param nameId        URL-safe slug, used as the local folder name
 * @param name          display name
 * @param summary       short description
 * @param author        submitter username
 * @param downloads     total download count
 * @param modfileId     id of the current file (0 if the mod has no file)
 * @param fileSize      size in bytes of the current file
 * @param md5           expected MD5 of the zip (may be empty)
 * @param downloadUrl   direct binary URL of the current file (may be empty)
 */
public record ModInfo(
        long id,
        String nameId,
        String name,
        String summary,
        String author,
        long downloads,
        long modfileId,
        long fileSize,
        String md5,
        String downloadUrl) {

    public boolean isDownloadable() {
        return modfileId > 0 && downloadUrl != null && !downloadUrl.isBlank();
    }
}
