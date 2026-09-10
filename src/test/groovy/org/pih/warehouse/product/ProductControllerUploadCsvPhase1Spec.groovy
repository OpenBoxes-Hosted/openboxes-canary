package org.pih.warehouse.product

import grails.testing.web.controllers.ControllerUnitTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import spock.lang.Specification

import java.nio.file.Files

import org.pih.warehouse.core.UploadService
import org.pih.warehouse.importer.ImportDataCommand

/**
 * uploadCsv() is Phase 1 of a two-phase flow: it stashes the uploaded file in session.localFile for
 * a later importCsv() request to read (P2.2-2 / M2), so - unlike the seven single-phase call sites -
 * it must NOT delete the file it just created. The only deletion Phase 1 does is of a PREVIOUS
 * upload it is about to replace, and that replace-and-delete happens under `synchronized (session)`
 * so two concurrent Phase-1 uploads in the same session cannot orphan a file between them (G2-2).
 *
 * productService is stubbed past its Phase-1 calls (getColumns, validateProducts) so the spec
 * exercises the real upload/session-locking code without needing a Grails/GORM context - the same
 * reasoning as PackingListControllerSpec's importer bypass.
 */
class ProductControllerUploadCsvPhase1Spec extends Specification implements ControllerUnitTest<ProductController> {

    File firstFile
    File secondFile
    UploadService uploadService

    void setup() {
        firstFile = Files.createTempFile('product-controller-spec', '.csv').toFile()
        secondFile = Files.createTempFile('product-controller-spec', '.csv').toFile()
        uploadService = Mock(UploadService)
        controller.uploadService = uploadService
        controller.productService = Mock(ProductService) {
            getColumns(_) >> []
            validateProducts(_, _) >> []
        }
        controller.request.method = 'POST'
    }

    void cleanup() {
        firstFile.delete()
        secondFile.delete()
    }

    void "uploadCsv() keeps a fresh session's first upload instead of deleting it"() {
        given: 'a session with no previous upload'
        ImportDataCommand command = new ImportDataCommand(
                importFile: new MockMultipartFile('importFile', 'products.csv', 'text/csv', 'a,b\n1,2'.bytes))

        when:
        controller.uploadCsv(command)

        then: 'the file was created from the name the client sent'
        1 * uploadService.createLocalFile('products.csv') >> firstFile

        and: 'the lock unconditionally calls deleteLocalFile with whatever session.localFile already was - ' +
                'null here, which the null-tolerant contract turns into a no-op, not the new upload'
        1 * uploadService.deleteLocalFile(null)

        and: 'and the new file is now what the session points at, for importCsv() to read next request'
        controller.session.localFile == firstFile
    }

    void "uploadCsv() replaces a session's previous upload under one lock"() {
        given: 'a session that already has an upload from an earlier Phase 1 request'
        controller.session.localFile = firstFile
        ImportDataCommand command = new ImportDataCommand(
                importFile: new MockMultipartFile('importFile', 'products.csv', 'text/csv', 'a,b\n3,4'.bytes))

        when:
        controller.uploadCsv(command)

        then: 'the second upload was created from the name the client sent'
        1 * uploadService.createLocalFile('products.csv') >> secondFile

        and: 'the first upload is deleted - the second replaces it before either is read'
        1 * uploadService.deleteLocalFile(firstFile)

        and: 'and the session now points at the second file'
        controller.session.localFile == secondFile
    }

    void "uploadCsv() deletes the just-created file when the transfer fails, and leaves the session untouched"() {
        given: 'a session that already has an upload from an earlier Phase 1 request'
        controller.session.localFile = firstFile

        and: 'a real UploadService for deleteLocalFile, so the assertion below is a real filesystem ' +
                'check and not just a recorded mock interaction - only createLocalFile is stubbed'
        uploadService = Spy(UploadService)
        controller.uploadService = uploadService

        and: 'a multipart file whose transferTo throws - disk full, an I/O error, an aborted upload'
        MultipartFile uploadFile = Mock(MultipartFile) {
            isEmpty() >> false
            getOriginalFilename() >> 'products.csv'
            transferTo(_ as File) >> { throw new IOException('disk full') }
        }
        ImportDataCommand command = new ImportDataCommand(importFile: uploadFile)

        when:
        controller.uploadCsv(command)

        then: 'the file was created from the name the client sent, before the transfer ever ran'
        1 * uploadService.createLocalFile('products.csv') >> secondFile

        and: 'and it was really deleted once the transfer failed - not left orphaned on disk'
        !secondFile.exists()

        and: 'the session still points at the PREVIOUS upload - Phase 1 never got far enough to replace it'
        controller.session.localFile == firstFile

        and: 'the existing error path ran exactly as before: caught, logged, surfaced as a command error'
        command.errors.hasErrors()
    }
}
