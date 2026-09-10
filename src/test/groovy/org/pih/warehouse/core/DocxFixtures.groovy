package org.pih.warehouse.core

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the smallest .docx that XDocReport will accept, so a spec can drive the real
 * document-template entry points.
 *
 * A .docx is a zip, and XDocReport reads the zip before it hands anything to the template
 * engine - so a plain string fixture would fail in the loader and a spec built on one would
 * pass for the wrong reason.
 */
class DocxFixtures {

    private static final String CONTENT_TYPES =
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
        '<Default Extension="xml" ContentType="application/xml"/>' +
        '<Override PartName="/word/document.xml"' +
        ' ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
        '</Types>'

    private static final String RELATIONSHIPS =
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1"' +
        ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"' +
        ' Target="word/document.xml"/>' +
        '</Relationships>'

    /**
     * @param text the body text of the single paragraph, XML-escaped by this method - pass
     *        template syntax exactly as an uploader would have typed it into Word.
     */
    static byte[] withBodyText(String text) {
        String document =
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
            '<w:body><w:p><w:r><w:t xml:space="preserve">' + escapeXml(text) + '</w:t></w:r></w:p></w:body>' +
            '</w:document>'

        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        ZipOutputStream zip = new ZipOutputStream(bytes)
        try {
            writeEntry(zip, '[Content_Types].xml', CONTENT_TYPES)
            writeEntry(zip, '_rels/.rels', RELATIONSHIPS)
            writeEntry(zip, 'word/document.xml', document)
        } finally {
            zip.close()
        }
        return bytes.toByteArray()
    }

    private static void writeEntry(ZipOutputStream zip, String name, String contents) {
        zip.putNextEntry(new ZipEntry(name))
        zip.write(contents.getBytes('UTF-8'))
        zip.closeEntry()
    }

    private static String escapeXml(String text) {
        return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    }
}
