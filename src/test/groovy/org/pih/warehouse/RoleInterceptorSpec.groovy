package org.pih.warehouse

import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class RoleInterceptorSpec extends Specification {

    // convenience method so we can write a big, expressive, expansive @Unroll table
    private boolean needRoleRouter(String controller, String action, String role) {
        switch (role) {
            case 'superuser':       return RoleInterceptor.needSuperuser(controller, action)
            case 'admin':           return RoleInterceptor.needAdmin(controller, action)
            case 'manager':         return RoleInterceptor.needManager(controller, action)
            case 'invoice':         return RoleInterceptor.needInvoice(controller, action)
            case 'product manager': return RoleInterceptor.needProductManager(controller, action)
            default:                throw new IllegalArgumentException("Unsupported role: ${role}")
        }
    }

    void "#controller.#action #requires '#role' access or above"() {
        expect:
        assert needRoleRouter(controller, action, role) == (requires == 'requires')

        where:
        role              | controller              | action                        || requires
        'superuser'       | 'console'               | 'execute'                     || 'requires'
        'superuser'       | 'console'               | 'index'                       || 'requires'
        'superuser'       | 'console'               | 'list'                        || 'does not require'
        // Both gate additions, asserted together: one changeset added the admin rows, another the
        // document rows, and a rebase that keeps only one half would otherwise pass both of the
        // task-local specs that cover them.
        'superuser'       | 'admin'                 | 'showDatabaseStatus'          || 'requires'
        'superuser'       | 'admin'                 | 'showDatabaseProcessList'     || 'requires'
        'superuser'       | 'admin'                 | 'showSettings'                || 'does not require'
        'superuser'       | 'document'              | 'create'                      || 'requires'
        'superuser'       | 'document'              | 'save'                        || 'requires'
        'superuser'       | 'document'              | 'update'                      || 'requires'
        'superuser'       | 'document'              | 'upload'                      || 'requires'
        'superuser'       | 'document'              | 'uploadDocument'              || 'does not require'
        'superuser'       | 'document'              | 'saveDocument'                || 'does not require'
        // documentType is the scaffolded DocumentTypeController (static scaffold = DocumentType).
        // documentCode is plain-bindable, so a manager could otherwise create or flip a type to
        // GSP_TEMPLATE and retro-promote an uploaded file into an executable template (H-27) --
        // the whole controller is gated, so read actions are superuser-only too (see below for
        // the arbitrary-action-name proof of the whole-controller gate).
        'superuser'       | 'documentType'          | 'create'                      || 'requires'
        'superuser'       | 'documentType'          | 'save'                        || 'requires'
        'superuser'       | 'documentType'          | 'edit'                        || 'requires'
        'superuser'       | 'documentType'          | 'update'                      || 'requires'
        'superuser'       | 'documentType'          | 'delete'                      || 'requires'
        'superuser'       | 'documentType'          | 'index'                       || 'requires'
        'superuser'       | 'documentType'          | 'list'                        || 'requires'
        'superuser'       | 'documentType'          | 'show'                        || 'requires'
        'superuser'       | 'inventory'             | 'createInboundTransfer'       || 'requires'
        'superuser'       | 'inventory'             | 'deleteTransaction'           || 'requires'
        'superuser'       | 'inventory'             | 'list'                        || 'does not require'
        'superuser'       | 'inventoryItem'         | 'adjustStock'                 || 'requires'
        'superuser'       | 'inventoryItem'         | 'transferStock'               || 'requires'
        'superuser'       | 'jobs'                  | 'list'                        || 'requires'
        'superuser'       | 'locationType'          | 'create'                      || 'requires'
        'superuser'       | 'productCatalog'        | 'create'                      || 'requires'
        'superuser'       | 'productType'           | 'edit'                        || 'requires'
        'superuser'       | 'quartz'                | 'index'                       || 'requires'
        'superuser'       | 'transactionEntry'      | 'delete'                      || 'requires'
        'superuser'       | 'user'                  | 'edit'                        || 'does not require'
        'superuser'       | 'user'                  | 'impersonate'                 || 'requires'
        'superuser'       | 'user'                  | 'list'                        || 'does not require'
        'admin'           | 'location'              | 'edit'                        || 'requires'
        'admin'           | 'location'              | 'list'                        || 'does not require'
        'admin'           | 'locationGroup'         | 'create'                      || 'requires'
        'admin'           | 'locationType'          | 'list'                        || 'requires'
        'admin'           | 'person'                | 'list'                        || 'requires'
        'admin'           | 'person'                | 'show'                        || 'does not require'
        'admin'           | 'product'               | 'create'                      || 'requires'
        'admin'           | 'product'               | 'edit'                        || 'does not require'
        'admin'           | 'product'               | 'list'                        || 'does not require'
        'admin'           | 'productSupplier'       | 'create'                      || 'requires'
        'admin'           | 'productSupplier'       | 'delete'                      || 'requires'
        'admin'           | 'productSupplier'       | 'edit'                        || 'requires'
        'admin'           | 'productSupplier'       | 'list'                        || 'does not require'
        'admin'           | 'shipper'               | 'create'                      || 'requires'
        'admin'           | 'user'                  | 'delete'                      || 'does not require'
        'admin'           | 'user'                  | 'deleteLocationRole'          || 'does not require'
        'admin'           | 'user'                  | 'edit'                        || 'does not require'
        'admin'           | 'user'                  | 'list'                        || 'requires'
        'admin'           | 'user'                  | 'save'                        || 'does not require'
        'admin'           | 'user'                  | 'saveLocationRole'            || 'does not require'
        'admin'           | 'user'                  | 'show'                        || 'does not require'
        'admin'           | 'user'                  | 'toggleActivation'            || 'does not require'
        'admin'           | 'user'                  | 'update'                      || 'does not require'
        'manager'         | 'inventory'             | 'createOutboundTransfer'      || 'requires'
        'manager'         | 'inventoryItem'         | 'showRecordInventory'         || 'requires'
        'manager'         | 'product'               | 'exportAsCsv'                 || 'requires'
        'manager'         | 'stockMovement'         | 'importOutboundStockMovement' || 'requires'
        'manager'         | 'stockMovementItemApi'  | 'eraseItem'                   || 'requires'
        'manager'         | 'user'                  | 'delete'                      || 'requires'
        'manager'         | 'user'                  | 'deleteLocationRole'          || 'requires'
        'manager'         | 'user'                  | 'edit'                        || 'does not require'
        'manager'         | 'user'                  | 'list'                        || 'does not require'
        'manager'         | 'user'                  | 'save'                        || 'requires'
        'manager'         | 'user'                  | 'saveLocationRole'            || 'requires'
        'manager'         | 'user'                  | 'show'                        || 'does not require'
        'manager'         | 'user'                  | 'toggleActivation'            || 'requires'
        'manager'         | 'user'                  | 'update'                      || 'requires'
        'invoice'         | 'invoice'               | 'list'                        || 'requires'
        'invoice'         | 'user'                  | 'list'                        || 'does not require'
        'product manager' | 'productSupplier'       | 'create'                      || 'requires'
        'product manager' | 'productSupplier'       | 'delete'                      || 'requires'
        'product manager' | 'productSupplier'       | 'edit'                        || 'requires'
        'product manager' | 'productSupplier'       | 'list'                        || 'does not require'
    }

    void "#controller.#prefix* requires 'manager' access or above"() {

        /*
         * All controllers should prefix-match in the same way;
         * I just picked a few representative ones for testing.
         * Feel free to add your favorite controller to the table.
         */
        expect:
        assert RoleInterceptor.needManager(controller, "${prefix}Something")
        assert !RoleInterceptor.needSuperuser(controller, "${prefix}Something")
        assert !RoleInterceptor.needInvoice(controller, "${prefix}Something")
        assert !RoleInterceptor.needProductManager(controller, "${prefix}Something")

        where:
        controller   | prefix
        'inventory'  | 'add'
        'inventory'  | 'cancel'
        'inventory'  | 'change'
        'inventory'  | 'create'
        'inventory'  | 'delete'
        'inventory'  | 'importData'
        'inventory'  | 'process'
        'inventory'  | 'receive'
        'inventory'  | 'save'
        'inventory'  | 'toggle'
        'inventory'  | 'update'
        'inventory'  | 'withdraw'
        'product'    | 'add'
        'product'    | 'create'
        'product'    | 'delete'
        'product'    | 'save'
        'product'    | 'update'
        'shipment'   | 'add'
        'shipment'   | 'create'
        'shipment'   | 'save'
        'user'       | 'add'
        'user'       | 'create'
        'user'       | 'delete'
        'user'       | 'save'
        'user'       | 'update'
    }

    void "#controller #requires '#role' access or above for all actions"() {
        expect:
        // under the covers, these controllers use wildcards
        assert needRoleRouter(controller, 'list', role) == (requires == 'requires')
        assert needRoleRouter(controller, 'show', role) == (requires == 'requires')
        assert needRoleRouter(controller, 'anyAction', role) == (requires == 'requires')
        assert needRoleRouter(controller, 'someOtherAction', role) == (requires == 'requires')
        assert needRoleRouter(controller, 'literallyAnything', role) == (requires == 'requires')

        where:
        controller                  | role        || requires
        // Whole-controller superuser gate for the scaffolded DocumentTypeController (H-27):
        // no action name -- including ones nobody has written yet -- escapes it.
        'documentType'              | 'superuser' || 'requires'
        'admin'                     | 'admin'     || 'requires'
        'admin'                     | 'manager'   || 'does not require'
        'admin'                     | 'invoice'   || 'does not require'
        'createProduct'             | 'admin'     || 'requires'
        'createProduct'             | 'manager'   || 'does not require'
        'createProduct'             | 'invoice'   || 'does not require'
        'createProductFromTemplate' | 'admin'     || 'requires'
        'createProductFromTemplate' | 'manager'   || 'requires'
        'createProductFromTemplate' | 'invoice'   || 'does not require'
    }
}
