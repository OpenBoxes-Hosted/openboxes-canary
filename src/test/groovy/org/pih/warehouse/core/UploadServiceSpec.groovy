package org.pih.warehouse.core

import grails.testing.services.ServiceUnitTest
import org.apache.commons.io.FilenameUtils
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Grails 3.3 resolves multipart requests with StandardServletMultipartResolver, whose
 * MultipartFile.originalFilename is the Content-Disposition filename verbatim - it does no
 * path stripping (CommonsMultipartFile does, which is why this is easy to miss). Everything
 * createLocalFile() is handed is therefore chosen by whoever sent the request.
 */
@Unroll
class UploadServiceSpec extends Specification implements ServiceUnitTest<UploadService> {

    @Shared
    File uploadsDirectory = new File(System.getProperty('java.io.tmpdir'),
            "upload-service-spec-${UUID.randomUUID()}")

    Closure doWithConfig() {
        return { config ->
            config.openboxes.uploads.location = uploadsDirectory.absolutePath
        }
    }

    void setup() {
        uploadsDirectory.mkdirs()
        service.fileService = Stub(FileService) {
            createDirectory(_ as String) >> { String path -> new File(path) }
        }
    }

    void cleanupSpec() {
        uploadsDirectory.deleteDir()
    }

    void "toSafeFilename reduces '#filename' to '#expected'"() {
        expect:
        UploadService.toSafeFilename(filename) == expected

        where:
        filename                                   || expected
        'products.xlsx'                            || 'products.xlsx'
        'products (2).xlsx'                        || 'products (2).xlsx'
        '../../etc/passwd'                         || 'passwd'
        '../../../usr/local/tomcat/webapps/x.jsp'  || 'x.jsp'
        '..\\..\\windows\\system32\\evil.bat'      || 'evil.bat'
        '/etc/passwd'                              || 'passwd'
        'sub/dir/products.xlsx'                    || 'products.xlsx'
        'C:\\Users\\ops\\products.xlsx'            || 'products.xlsx'
        // A URL-encoded traversal belongs HERE and not in the refusal list: nothing between the
        // multipart parser and the filesystem URL-decodes this string, so '%2f' is an ordinary
        // filename character. The name it reduces to is itself, and the file lands in the uploads
        // directory under that literal name - which the createLocalFile feature below then proves.
        '..%2f..%2fetc%2fpasswd'                   || '..%2f..%2fetc%2fpasswd'
    }

    void "toSafeFilename refuses '#filename', which names no file"() {
        when:
        UploadService.toSafeFilename(filename)

        then:
        thrown(IllegalArgumentException)

        where:
        // The NUL row is the one that would regress silently: commons-io 2.12 throws on an embedded
        // NUL, but the version is only force'd (build.gradle:307), not declared, so a downgrade or a
        // dependency-resolution change could hand back a name a filesystem truncates at the NUL -
        // 'products.jsp\u0000.png' passing an extension allowlist and landing on disk as
        // 'products.jsp'. Written as the Java escape inside a Groovy string, which interpolates it.
        filename << [null, '', '   ', '.', '..', '../', '..\\', 'dir/', 'dir\\',
                     "products.jsp\u0000.png"]
    }

    void "createLocalFile keeps '#filename' inside the uploads directory"() {
        when:
        File localFile = service.createLocalFile(filename)

        then: 'the file was created, not merely named - which is what makes the next assertion a real check'
        localFile.exists()

        and: 'its CANONICAL parent is the CANONICAL uploads directory: symlinks resolved, .. resolved'
        localFile.canonicalFile.parentFile.canonicalPath == uploadsDirectory.canonicalFile.canonicalPath

        and: 'and it still carries the base name the client sent, so the file is recognisable'
        localFile.name.endsWith(FilenameUtils.getName(filename))

        cleanup:
        localFile?.delete()

        where:
        filename << ['products.xlsx', '../../etc/passwd', 'sub/dir/products.xlsx',
                     '../../../usr/local/tomcat/webapps/ROOT/shell.jsp',
                     '..%2f..%2fetc%2fpasswd']
    }

    void "toSafeFilename caps an oversized name to 200 characters while preserving the extension"() {
        given: 'createLocalFile prepends its own ~19-character random prefix on top of whatever ' +
                'toSafeFilename returns, so an unbounded name can push the final on-disk name past ' +
                'the 255-byte filesystem limit that upstream\'s direct new File(dir, name) stayed under'
        String oversizedName = ('a' * 250) + '.xlsx'

        when:
        String result = UploadService.toSafeFilename(oversizedName)

        then:
        result.length() <= 200
        result.endsWith('.xlsx')
    }

    void "toSafeFilename leaves '#filename' unchanged when it is already at or under 200 characters"() {
        expect:
        UploadService.toSafeFilename(filename) == filename

        where:
        filename << ['products.xlsx', 'a' * 199]
    }

    void "uploadMutex returns the same object for repeated calls against the same session"() {
        given: 'the container session mutex two-phase upload flows must lock on instead of the ' +
                'Grails session property (I1) - WebUtils.getSessionMutex(session) is the ' +
                'SESSION_MUTEX_ATTRIBUTE if a listener set one, else the HttpSession itself, which the ' +
                'servlet container keeps as one facade per session'
        MockHttpServletRequest request = new MockHttpServletRequest()

        expect: 'two calls against the same underlying session resolve to the identical object'
        UploadService.uploadMutex(request).is(UploadService.uploadMutex(request))
    }

    void "createLocalFile gives two uploads of the same name two different files"() {
        when:
        File first = service.createLocalFile('products.xlsx')
        File second = service.createLocalFile('products.xlsx')

        then: 'the second upload cannot overwrite the first before either has been parsed'
        first.absolutePath != second.absolutePath

        and: 'and both still end in the name the client sent'
        first.name.endsWith('products.xlsx')
        second.name.endsWith('products.xlsx')

        cleanup:
        first?.delete()
        second?.delete()
    }

    void "deleteLocalFile removes the file"() {
        given:
        File localFile = service.createLocalFile('products.xlsx')
        localFile.text = 'a spreadsheet, allegedly'

        when:
        boolean deleted = service.deleteLocalFile(localFile)

        then:
        deleted
        !localFile.exists()
    }

    void "deleteLocalFile tolerates a file that is not there"() {
        expect:
        !service.deleteLocalFile(null)
        !service.deleteLocalFile(new File(uploadsDirectory, 'never-written.xlsx'))
    }

}
