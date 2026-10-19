package io.github.sunjirapath.ssg.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTablesTest {

    @Test
    void selectsTableAfterFrom() {
        assertThat(JdbcExtractor.tables("SELECT id FROM Orders")).containsExactly("orders");
    }

    @Test
    void extractsEveryExplicitJoinKind() {
        assertThat(JdbcExtractor.tables("""
                SELECT * FROM base
                JOIN inner_table i ON true
                INNER JOIN inner_keyword i2 ON true
                LEFT JOIN left_table l ON true
                RIGHT JOIN right_table r ON true
                FULL JOIN full_table f ON true
                CROSS JOIN cross_table c
                """)).containsExactly("base", "inner_table", "inner_keyword", "left_table", "right_table", "full_table", "cross_table");
    }

    @Test
    void extractsInsertTarget() {
        assertThat(JdbcExtractor.tables("INSERT INTO Audit_Log(id) VALUES (1)")).containsExactly("audit_log");
    }

    @Test
    void extractsUpdateTarget() {
        assertThat(JdbcExtractor.tables("UPDATE Customer SET active = true")).containsExactly("customer");
    }

    @Test
    void extractsDeleteTarget() {
        assertThat(JdbcExtractor.tables("DELETE FROM Session_Token WHERE expired = true")).containsExactly("session_token");
    }

    @Test
    void extractsMergeTarget() {
        assertThat(JdbcExtractor.tables("MERGE INTO Inventory i USING incoming n ON i.id = n.id")).containsExactly("inventory");
    }

    @Test
    void removesSchemaQualification() {
        assertThat(JdbcExtractor.tables("SELECT * FROM Reporting.Monthly_Sales")).containsExactly("monthly_sales");
    }

    @Test
    void removesSupportedIdentifierQuotes() {
        assertThat(JdbcExtractor.tables("SELECT * FROM \"MixedCase\" JOIN `BackTicked` b ON true JOIN [Bracketed] c ON true"))
                .containsExactly("mixedcase", "backticked", "bracketed");
    }

    @Test
    void lowerCasesNamesPreservingFirstAppearanceOrderAndCollapsingDuplicates() {
        assertThat(JdbcExtractor.tables("SELECT * FROM ZEBRA JOIN alpha ON true JOIN Zebra z2 ON true JOIN Beta ON true"))
                .containsExactly("zebra", "alpha", "beta");
    }

    @Test
    void excludesPlainCteNameButKeepsItsPhysicalTable() {
        assertThat(JdbcExtractor.tables("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"))
                .containsExactly("orders");
    }

    @Test
    void excludesSingleRecursiveAndMultipleCteNamesButKeepsTheirPhysicalTables() {
        assertThat(JdbcExtractor.tables("""
                WITH RECURSIVE tree AS (SELECT * FROM nodes),
                     a AS (SELECT * FROM accounts),
                     b AS (SELECT * FROM balances)
                SELECT * FROM tree JOIN a ON true JOIN b ON true
                """)).containsExactly("nodes", "accounts", "balances");
    }

    @Test
    void subqueryAliasIsNotATableButNestedPhysicalTableIs() {
        assertThat(JdbcExtractor.tables("SELECT * FROM (SELECT * FROM Orders) recent"))
                .containsExactly("orders");
    }

    @Test
    void ignoresCommentsAndStringLiteralsThatLookLikeSql() {
        assertThat(JdbcExtractor.tables("""
                SELECT 'FROM fake_literal'
                FROM real_table
                -- JOIN fake_line ON true
                /* INSERT INTO fake_block(id) VALUES (1) */
                """)).containsExactly("real_table");
    }

    @Test
    void ignoresFromAndUpdateKeywordsInsideSqlFunctions() {
        assertThat(JdbcExtractor.tables("""
                SELECT EXTRACT(YEAR FROM created_at),
                       TRIM(prefix FROM label),
                       SUBSTRING(label FROM 2),
                       POSITION(needle IN label)
                FROM events
                """)).containsExactly("events");
    }

    @Test
    void ignoresTrailingLockAndUpsertClauses() {
        assertThat(JdbcExtractor.tables("""
                SELECT * FROM jobs FOR UPDATE;
                INSERT INTO metrics(id) VALUES (1) ON DUPLICATE KEY UPDATE count = 1;
                INSERT INTO users(id) VALUES (1) ON CONFLICT (id) DO UPDATE SET active = true;
                """)).containsExactly("jobs", "metrics", "users");
    }

    @Test
    void ignoresDualAndNumericPseudoTables() {
        assertThat(JdbcExtractor.tables("SELECT 1 FROM dual; SELECT * FROM 123"))
                .isEmpty();
    }

    @Test
    void implicitCommaJoinYieldsEveryTable() {
        assertThat(JdbcExtractor.tables("SELECT * FROM first_table, second_table"))
                .containsExactly("first_table", "second_table");
    }

    @Test
    void partiallyUnknownTableNameIsNotInvented() {
        assertThat(JdbcExtractor.tables("SELECT * FROM orders_{?}"))
                .isEmpty();
    }

    @Test
    void readsSqlContainingNewlines() {
        assertThat(JdbcExtractor.tables("""
                SELECT o.id
                FROM orders o
                LEFT JOIN order_lines l
                  ON l.order_id = o.id
                """)).containsExactly("orders", "order_lines");
    }

    @Test
    void malformedOrEmptySqlProducesNoTables() {
        assertThat(JdbcExtractor.tables("FROM /* unterminated comment"))
                .isEmpty();
        assertThat(JdbcExtractor.tables(""))
                .isEmpty();
    }
}
