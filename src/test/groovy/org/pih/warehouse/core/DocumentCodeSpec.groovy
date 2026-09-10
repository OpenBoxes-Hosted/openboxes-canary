package org.pih.warehouse.core

import spock.lang.Specification

/**
 * Template documents (GSP/Freemarker/Velocity/JXLS) and DATA_EXPORT documents are both executed
 * or evaluated rather than just stored - a DATA_EXPORT document's bytes are run as SQL by
 * DataExportController. executableList() is the write-gate's source of truth for which document
 * codes may only be created or re-typed by a superuser; it must stay a superset of templateList().
 */
class DocumentCodeSpec extends Specification {

    void "executableList contains every templateList code plus DATA_EXPORT, and nothing else"() {
        given:
        List<DocumentCode> executable = DocumentCode.executableList()
        List<DocumentCode> templates = DocumentCode.templateList()

        expect:
        executable.containsAll(templates)
        executable.contains(DocumentCode.DATA_EXPORT)
        executable.size() == templates.size() + 1
        !templates.contains(DocumentCode.DATA_EXPORT)
    }
}
