package labs.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 导入一行：先写入，再校验；校验失败时这一行已经写进了当前事务。 */
@Service
public class RowImporter {
    private final JdbcTemplate jdbc;

    public RowImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static class InvalidRowException extends RuntimeException {
        public InvalidRowException(long id) {
            super("invalid row " + id);
        }
    }

    @Transactional(propagation = Propagation.NESTED)
    public void importNested(long id, String val) {
        insertThenValidate(id, val);
    }

    @Transactional
    public void importRequired(long id, String val) {
        insertThenValidate(id, val);
    }

    private void insertThenValidate(long id, String val) {
        jdbc.update("INSERT INTO import_rows VALUES (?, ?)", id, val);
        if (val.isBlank()) {
            throw new InvalidRowException(id);
        }
    }
}
