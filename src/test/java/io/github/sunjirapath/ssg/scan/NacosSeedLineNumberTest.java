package io.github.sunjirapath.ssg.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NacosSeedLineNumberTest {

    @Test
    void multilineAndMultipleInsertsKeepTupleStartLines() {
        String sql = """
                -- seed
                INSERT INTO config_info (data_id, content) VALUES
                ('one.yml', 'escaped\\nvalue'),
                ('two.yml',
                 'multi
                line');
                INSERT INTO config_info (data_id, content) VALUES
                ('three.yml', 'last');
                """;

        assertThat(NacosSeed.parse(Path.of("seed.sql"), sql))
                .extracting(NacosSeed.Row::line)
                .containsExactly(3, 4, 8);
    }
}
