package org.pih.warehouse.outbound

import grails.testing.web.controllers.ControllerUnitTest
import org.springframework.mock.web.MockMultipartFile
import spock.lang.Specification

import java.nio.file.Files

import org.pih.warehouse.core.UploadService
import org.pih.warehouse.importer.ImportDataCommand

/**
 * The bytes are deliberately not a spreadsheet: PackingListExcelImporter's constructor calls
 * AbstractExcelImporter.read(), which hands the file to POI's WorkbookFactory before it looks up
 * any Grails bean, so garbage bytes fail there and the spec needs no application context. That
 * failure is also the point - the uploaded file has to be cleaned up on the way out, not only when
 * the parse succeeds.
 */
class PackingListControllerSpec extends Specification implements ControllerUnitTest<PackingListController> {

    File localFile
    UploadService uploadService

    void setup() {
        // Files.createTempFile, not File.createTempFile: the java.io form has a documented race
        // (it checks then creates) and creates the file world-readable on POSIX systems. The nio
        // form creates it atomically with owner-only permissions. Same reason the production code
        // uses it in Task P2.2-1.
        localFile = Files.createTempFile('packing-list-controller-spec', '.xlsx').toFile()
        uploadService = Mock(UploadService)
        controller.uploadService = uploadService
    }

    void cleanup() {
        localFile.delete()
    }

    void "upload() deletes the uploaded file even when the import blows up"() {
        given:
        ImportDataCommand command = new ImportDataCommand(
                importFile: new MockMultipartFile('importFile', 'packing-list.xlsx',
                        'application/vnd.ms-excel', 'not a spreadsheet'.bytes))

        when:
        controller.upload(command)

        then: 'the file was created from the name the client sent'
        1 * uploadService.createLocalFile('packing-list.xlsx') >> localFile

        and: 'the importer rejected the bytes'
        thrown(Exception)

        and: 'and the file was deleted on the way out anyway'
        1 * uploadService.deleteLocalFile(localFile)
    }
}
