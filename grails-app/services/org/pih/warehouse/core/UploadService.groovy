/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.core

import grails.core.GrailsApplication
import org.apache.commons.io.FilenameUtils
import org.springframework.web.util.WebUtils

import javax.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class UploadService {

    GrailsApplication grailsApplication
    FileService fileService

    /**
     * Create a file in the uploads directory for one upload, and prove it is in there.
     *
     * The name is reduced to its base name first, then the file is CREATED inside the uploads
     * directory rather than merely named after it. Creating it is what makes the check below
     * meaningful: comparing canonical paths after the fact resolves symlinks and any remaining
     * relative segment, which a string test on the name cannot do. It also makes the name unique
     * per call, so two people importing "products.xlsx" at the same moment no longer write to the
     * same path and overwrite each other before either file has been parsed.
     */
    File createLocalFile(String filename) {
        String safeFilename = toSafeFilename(filename)
        log.info "Create local file ${safeFilename}"
        File uploadsDirectory = findOrCreateUploadsDirectory()

        // An empty prefix is allowed here: the three-character minimum is java.io.File.createTempFile's
        // rule, not java.nio.file.Files'.
        File localFile = Files.createTempFile(uploadsDirectory.toPath(), "", "-${safeFilename}").toFile()

        File canonicalUploadsDirectory = uploadsDirectory.canonicalFile
        if (localFile.canonicalFile.parentFile != canonicalUploadsDirectory) {
            localFile.delete()
            throw new IllegalArgumentException(
                    "Refusing to use ${localFile.canonicalPath}: it is not in the uploads directory " +
                            "${canonicalUploadsDirectory.path}")
        }
        return localFile
    }

    // createLocalFile prepends its own ~19-character random numeric prefix on top of whatever this
    // returns (Files.createTempFile's middle segment), so an unbounded name can push the final
    // on-disk name past the 255-BYTE filename limit that upstream's direct new File(dir, name)
    // stayed under (G3) - a filesystem limit is a BYTE limit, not a UTF-16 char-count limit, so the
    // budget below is spent in UTF-8 bytes. 200 leaves comfortable headroom for that prefix plus the
    // separators. MAX_EXTENSION_LENGTH stays a char count: it is only used to decide whether a
    // dotted suffix looks like a real extension (a short one) rather than, say, a sentence with a
    // period in it - not to size anything written to disk.
    static final int MAX_SAFE_FILENAME_BYTES = 200
    static final int MAX_EXTENSION_LENGTH = 20

    /**
     * Reduce a name supplied by an upload to the file name it is allowed to be.
     *
     * MultipartFile.originalFilename is not a safe path component. Grails resolves multipart
     * requests with Spring's StandardServletMultipartResolver, and StandardMultipartFile returns
     * the Content-Disposition filename exactly as it was sent - unlike CommonsMultipartFile, which
     * strips the directory part itself. So the caller may be handing us "../../somewhere/else".
     */
    static String toSafeFilename(String filename) {
        String name = FilenameUtils.getName(filename)
        if (!name?.trim() || name == '.' || name == '..') {
            throw new IllegalArgumentException("Uploaded file must have a file name: ${filename}")
        }
        return capLength(name)
    }

    /**
     * Truncate a name to MAX_SAFE_FILENAME_BYTES UTF-8 bytes, keeping the extension (the part after
     * the last dot) intact when there is one and it is short enough to be a real extension.
     *
     * The budget is spent in UTF-8 BYTES, not java.lang.String's UTF-16 char count: a filesystem's
     * name-length limit is a byte limit, and a single character can be anywhere from 1 byte (ASCII)
     * to 4 bytes (CJK is typically 3; a supplementary-plane emoji is 4) in UTF-8. A char-count cap
     * therefore let a ~200-character non-ASCII name through at 400-600+ bytes - the exact "File name
     * too long" failure this method exists to prevent.
     *
     * The stem is truncated by iterating whole CODE POINTS, never a raw char/byte offset, so a
     * surrogate pair (a supplementary-plane character, encoded as two UTF-16 chars) is never split -
     * that would leave a lone, unpaired surrogate in the file name.
     */
    private static String capLength(String name) {
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_SAFE_FILENAME_BYTES) {
            return name
        }
        int dotIndex = name.lastIndexOf('.')
        boolean hasKeepableExtension = dotIndex > 0 &&
                (name.length() - dotIndex - 1) <= MAX_EXTENSION_LENGTH
        String extension = hasKeepableExtension ? name.substring(dotIndex) : ""
        String stem = hasKeepableExtension ? name.substring(0, dotIndex) : name

        int[] stemCodePoints = stem.codePoints().toArray()
        int extensionBytes = extension.getBytes(StandardCharsets.UTF_8).length
        int stemBudget = MAX_SAFE_FILENAME_BYTES - extensionBytes

        if (stemCodePoints.length > 0) {
            String firstCodePoint = new String(Character.toChars(stemCodePoints[0]))
            int firstCodePointBytes = firstCodePoint.getBytes(StandardCharsets.UTF_8).length
            if (firstCodePointBytes > stemBudget) {
                // The extension alone would leave no room for even the first stem code point: an
                // empty, extension-only name is less useful than a one-code-point name with no
                // extension, so drop the extension rule entirely here.
                return firstCodePoint
            }
        }

        StringBuilder truncatedStem = new StringBuilder()
        int usedBytes = 0
        for (int codePoint : stemCodePoints) {
            String codePointString = new String(Character.toChars(codePoint))
            int codePointBytes = codePointString.getBytes(StandardCharsets.UTF_8).length
            if (usedBytes + codePointBytes > stemBudget) {
                break
            }
            truncatedStem.append(codePointString)
            usedBytes += codePointBytes
        }
        return truncatedStem.toString() + extension
    }

    /**
     * The mutex the two-phase upload flows (Phase 1's replace-and-swap, Phase 2's read) must lock
     * on to serialise concurrent access to the SAME upload within one HTTP session (I1).
     *
     * Grails' own `session` property is not safe to synchronize on: GrailsWebRequest#getSession()
     * lazily builds a NEW GrailsHttpSession(request) on every call and caches it on the
     * GrailsWebRequest - itself a per-request object - and GrailsHttpSession overrides neither
     * equals() nor hashCode(). Two concurrent requests in the same HTTP session therefore
     * synchronize on two different objects and never actually exclude each other; the lock does
     * nothing.
     *
     * WebUtils.getSessionMutex() resolves to Spring's own SESSION_MUTEX_ATTRIBUTE - set on every
     * session at creation by the HttpSessionMutexListener registered in
     * grails-app/conf/spring/resources.groovy - which is a guaranteed one-object-per-session mutex
     * regardless of servlet container. Without that listener, getSessionMutex() falls back to the
     * HttpSession object itself; that fallback is WebUtils' documented behaviour, not this method's
     * guarantee, and relies on a container implementation detail (a stable per-session HttpSession
     * facade) that the Servlet spec does not require - which is exactly why the listener is
     * registered rather than relied on to be unnecessary.
     */
    static Object uploadMutex(HttpServletRequest request) {
        return WebUtils.getSessionMutex(request.getSession())
    }

    /**
     * Delete a file created by {@link #createLocalFile}. Uploads are parsed into memory and then
     * finished with, so keeping them leaves every uploaded spreadsheet on the instance for the life
     * of the process. Null-tolerant and non-throwing: callers use this on their unwind path.
     */
    boolean deleteLocalFile(File localFile) {
        if (!localFile) {
            return false
        }
        boolean deleted = localFile.delete()
        if (!deleted && localFile.exists()) {
            log.warn("Unable to delete uploaded file ${localFile.absolutePath}")
        }
        return deleted
    }

    File findOrCreateUploadsDirectory() {
        String directoryPath = grailsApplication.config.openboxes.uploads.location
        log.info("Find or create uploads directory ${directoryPath}")
        if (!directoryPath) {
            throw new IllegalStateException("Directory path for uploads directory must be configured in openboxes-config.properties [openboxes.uploads.location]")
        }
        // Replace tilde with user home
        directoryPath = directoryPath.replaceFirst("^~", System.getProperty("user.home"))
        return fileService.createDirectory(directoryPath)
    }

}
