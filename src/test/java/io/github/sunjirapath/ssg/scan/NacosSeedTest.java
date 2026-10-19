package io.github.sunjirapath.ssg.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NacosSeedTest {

    private static final Path FILE = Path.of("seed.sql");

    @Test
    void stringEscapesAreDecoded() {
        var rows = NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 'r\\r\\0x\\qy''z');");

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.content()).isEqualTo("r\r\0xqy'z"));
    }

    @Test
    void truncatedInputKeepsWhatWasRead() {
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 'tail\\"))
                .singleElement().satisfies(r -> assertThat(r.content()).isEqualTo("tail\\"));
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 'q'")).hasSize(1);
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 5")).hasSize(1);
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES (")).isEmpty();
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 'x') ;")).hasSize(1);
        assertThat(NacosSeed.parse(FILE, "INSERT INTO config_info (data_id, content) VALUES ('a.yml', 'it''")).hasSize(1);
    }

    @Test
    void rowKindsAndStems() {
        var row = new NacosSeed.Row(FILE, "app-dev.yaml", "", 1);
        assertThat(row.isConfig()).isTrue();
        assertThat(row.stem()).isEqualTo("app-dev");
        assertThat(new NacosSeed.Row(FILE, "plain", "", 1).stem()).isEqualTo("plain");
        assertThat(new NacosSeed.Row(FILE, "plain", "", 1).isConfig()).isFalse();
    }
}
