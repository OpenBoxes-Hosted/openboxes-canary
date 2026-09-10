package org.pih.warehouse.product

import grails.testing.web.controllers.ControllerUnitTest
import org.springframework.web.multipart.MultipartFile
import spock.lang.Specification

import java.nio.file.Files

import org.pih.warehouse.core.UploadService
import org.pih.warehouse.importer.ImportDataCommand

/**
 * importProductSynonyms() reads its upload via request.getFile("file") (a
 * MultipartHttpServletRequest cast) rather than command.importFile, so the fixture wires the file
 * through GrailsMockHttpServletRequest#addFile - the same request class the running app supplies at
 * runtime (confirmed empirically: `request instanceof MultipartHttpServletRequest` is true in a
 * plain ControllerUnitTest) - instead of setting a command property, following
 * ProductControllerUploadCsvPhase1Spec's real-deletion pattern: uploadService is a Spy with only
 * createLocalFile stubbed, so deleteLocalFile really runs and the file-existence assertion below is
 * a genuine filesystem check, not a recorded mock interaction.
 */
class ProductControllerImportProductSynonymsSpec extends Specification implements ControllerUnitTest<ProductController> {

    File localFile
    UploadService uploadService

    void setup() {
        localFile = Files.createTempFile('product-controller-synonyms-spec', '.csv').toFile()
        uploadService = Spy(UploadService)
        controller.uploadService = uploadService
        controller.request.method = 'POST'
    }

    void cleanup() {
        localFile.delete()
    }

    void "importProductSynonyms() deletes the just-created file when the transfer fails"() {
        given: 'a multipart file whose transferTo throws - disk full, an I/O error, an aborted upload'
        MultipartFile uploadFile = Mock(MultipartFile) {
            getName() >> 'file'
            isEmpty() >> false
            getOriginalFilename() >> 'synonyms.csv'
            transferTo(_ as File) >> { throw new IOException('disk full') }
        }
        request.addFile(uploadFile)
        ImportDataCommand command = new ImportDataCommand()

        when:
        controller.importProductSynonyms(command)

        then: 'the file was created from the name the client sent, before the transfer ever ran'
        1 * uploadService.createLocalFile('synonyms.csv') >> localFile

        and: 'and it was really deleted once the transfer failed - not left orphaned on disk'
        !localFile.exists()

        and: 'the existing error path ran exactly as before: flash set, redirected back to edit'
        flash.error == 'Unable to upload file due to exception: disk full'
        response.redirectedUrl == '/product/edit'
    }
}
