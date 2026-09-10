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

enum DocumentCode {

    IMAGE,
    THUMBNAIL,
    PRODUCT_MANUAL,
    PURCHASE_ORDER_TEMPLATE,
    SHIPPING_DOCUMENT,
    SHIPPING_TEMPLATE,
    SHIPPING_XLS_TEMPLATE,
    ZEBRA_TEMPLATE,
    EMAIL_TEMPLATE,
    DATA_EXPORT,
    INVOICE_TEMPLATE,
    REQUISITION_TEMPLATE,
    GSP_TEMPLATE,

    static list() {
        [
                THUMBNAIL,
                PRODUCT_MANUAL,
                PURCHASE_ORDER_TEMPLATE,
                SHIPPING_DOCUMENT,
                SHIPPING_TEMPLATE,
                SHIPPING_XLS_TEMPLATE,
                ZEBRA_TEMPLATE,
                EMAIL_TEMPLATE,
                DATA_EXPORT,
                INVOICE_TEMPLATE,
                REQUISITION_TEMPLATE,
                GSP_TEMPLATE,
        ]
    }

    static templateList() {
        [
                PURCHASE_ORDER_TEMPLATE,
                SHIPPING_TEMPLATE,
                SHIPPING_XLS_TEMPLATE,
                ZEBRA_TEMPLATE,
                EMAIL_TEMPLATE,
                INVOICE_TEMPLATE,
                REQUISITION_TEMPLATE,
                GSP_TEMPLATE,
        ]
    }

    static shipmentWorkflowTemplateList() {
        [
                SHIPPING_TEMPLATE,
                SHIPPING_XLS_TEMPLATE,
                INVOICE_TEMPLATE,
        ]
    }

    /**
     * Document codes whose document bytes are executed or evaluated, rather than just stored or
     * downloaded: the template codes (rendered through GSP/Freemarker/Velocity/JXLS) plus
     * DATA_EXPORT, whose bytes are run as a SQL query by DataExportController. This is the
     * write-gate's source of truth for which document codes may only be created or re-typed by a
     * superuser.
     */
    static executableList() {
        templateList() + [DATA_EXPORT]
    }
}
