package org.pih.warehouse.migrations

import spock.lang.Shared
import spock.lang.Specification

/**
 * The migrations README teaches a precondition pattern by example. The worked example is quoted
 * verbatim from the fork's already-converted changelog (see the README's own attribution note), so
 * this spec cannot pin the quote against a changelog in this tree - upstream's changelogs are being
 * converted separately, on their own timeline. Instead it asserts the embedded example is shaped the
 * way the README claims: scoped by DATABASE(), and not a name-only precondition.
 */
class MigrationPreconditionPatternSpec extends Specification {

    @Shared
    String readme

    void setupSpec() {
        readme = new File('grails-app/migrations/README.md').text
    }

    void 'the README documents the scope predicate that makes a precondition single-schema'() {
        expect:
        readme.contains('CONSTRAINT_SCHEMA = DATABASE()')
    }

    void 'the README worked example is scoped, not name-only'() {
        given: 'the changeSet the README embeds as its worked example'
        String example = readme.find(/(?s)<changeSet[^>]*>.*?<\/changeSet>/)

        expect:
        example
        example.contains('information_schema')
        example.contains('DATABASE()')
        !example.contains('foreignKeyConstraintExists')
        !example.contains('indexExists')
    }

    void 'the README names both precondition types the guidance covers'() {
        expect:
        readme.contains('foreignKeyConstraintExists')
        readme.contains('indexExists')
    }
}
