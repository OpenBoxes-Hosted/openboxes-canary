# Writing database migrations

Changelogs in this directory are applied by Liquibase at boot (see `BootStrap.groovy`) and are
organised by the version that introduced them. `install/` holds the consolidated changelogs a new
database runs; the version folders hold the incremental ones an existing database upgrades through.

## Preconditions on foreign keys and indexes must name a schema

Prefer a scoped `sqlCheck` over `foreignKeyConstraintExists` or `indexExists` when the precondition
guards an `addForeignKeyConstraint` or a `createIndex`. Both of those built-in preconditions match
**by name only**, with no schema predicate, so on a server where the migrating account can see more
than one OpenBoxes database they answer "yes" for a constraint that belongs to a different one -
and the changeset is marked as run without ever creating the constraint it was written to create.

There is a second, unrelated cost. When the constraint is absent - which is the normal case, since
that is why the changeset exists - a name-only `foreignKeyConstraintExists` makes Liquibase snapshot
every foreign key in the schema, which with Connector/J means one `SHOW CREATE TABLE` per table.
On a populated database that has been measured at 29 to 419 seconds for a single changeset.

A scoped `sqlCheck` answers the same question with one indexed query against `information_schema`,
and `DATABASE()` pins it to the database the migration is actually running against. The example below
is quoted verbatim from the `canary` fork's event-log changelog (already converted there); this
upstream tree's own changelogs are being converted separately, on their own timeline:

    <changeSet author="ewaterman" id="100220260000-1">
        <preConditions onFail="MARK_RAN">
            <sqlCheck expectedResult="0">
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'event_log'
                  AND CONSTRAINT_NAME = 'fk_event_log_event'
                  AND CONSTRAINT_TYPE = 'FOREIGN KEY'
            </sqlCheck>
        </preConditions>
        <addForeignKeyConstraint
                baseColumnNames="event_id"
                baseTableName="event_log"
                constraintName="fk_event_log_event"
                deferrable="false"
                initiallyDeferred="false"
                referencedColumnNames="id"
                referencedTableName="event"
        />
    </changeSet>

Note the sense of the check: `expectedResult="0"` means "the constraint is not there yet, so run
the changeset". The equivalent for an index queries `information_schema.STATISTICS` with
`TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '...' AND INDEX_NAME = '...'`.

This is guidance for **new** changelogs. Historical changelogs still use the name-only form; they
are being converted separately, and a conversion is not something to fold into an unrelated change.

A note on `schemaName`: the attribute on `indexExists` and friends names a **database**, not a
table or a view. Passing a table or view name there is silently discarded by Liquibase 3.x, so the
precondition still matches across schemas while looking as though it does not.

## Before you open the pull request

Run the migration against both databases the CI matrix covers - see `src/integration-test/README.md`:

    TEST_DATABASE=mysql:8.0.36    ./gradlew integrationTest
    TEST_DATABASE=mariadb:10.3.39 ./gradlew integrationTest
