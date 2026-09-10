package org.pih.warehouse.outbound

import grails.converters.JSON
import org.pih.warehouse.core.UploadService
import org.pih.warehouse.importer.DataImporter
import org.pih.warehouse.importer.ImportDataCommand
import org.pih.warehouse.importer.PackingListExcelImporter
import org.springframework.web.multipart.MultipartFile

class PackingListController {

    UploadService uploadService

    def upload(ImportDataCommand command) {
        MultipartFile importFile = command.importFile
        File localFile = uploadService.createLocalFile(importFile.originalFilename)
        DataImporter packingListImporter
        try {
            importFile.transferTo(localFile)
            // the importer reads the whole workbook into memory, so the file is finished with here
            packingListImporter = new PackingListExcelImporter(localFile.absolutePath)
        } finally {
            uploadService.deleteLocalFile(localFile)
        }

        render([data: packingListImporter.data] as JSON)
    }
}
