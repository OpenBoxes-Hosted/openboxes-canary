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
    // on-disk name past the 255-byte filename limit that upstream's direct new File(dir, name)
    // stayed under (G3). 200 leaves comfortable headroom for that prefix plus the separators.
    static final int MAX_SAFE_FILENAME_LENGTH = 200
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
     * Truncate a name to MAX_SAFE_FILENAME_LENGTH, keeping the extension (the part after the last
     * dot) intact when there is one and it is short enough to be a real extension rather than, say,
     * a sentence with a period in it.
     */
    private static String capLength(String name) {
        if (name.length() <= MAX_SAFE_FILENAME_LENGTH) {
            return name
        }
        int dotIndex = name.lastIndexOf('.')
        boolean hasKeepableExtension = dotIndex > 0 &&
                (name.length() - dotIndex - 1) <= MAX_EXTENSION_LENGTH
        String extension = hasKeepableExtension ? name.substring(dotIndex) : ""
        String stem = hasKeepableExtension ? name.substring(0, dotIndex) : name
        int maxStemLength = MAX_SAFE_FILENAME_LENGTH - extension.length()
        return stem.substring(0, Math.min(stem.length(), maxStemLength)) + extension
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
