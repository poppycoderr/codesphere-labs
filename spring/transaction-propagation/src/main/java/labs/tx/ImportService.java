package labs.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批量导入：外层一个事务，逐行调用 RowImporter，失败的行记录下来继续下一行。 */
@Service
public class ImportService {
    private final RowImporter importer;

    public ImportService(RowImporter importer) {
        this.importer = importer;
    }

    @Transactional
    public List<Long> importBatch(Map<Long, String> rows, boolean nested) {
        List<Long> failures = new ArrayList<>();
        rows.forEach((id, val) -> {
            try {
                if (nested) {
                    importer.importNested(id, val);
                } else {
                    importer.importRequired(id, val);
                }
            } catch (RowImporter.InvalidRowException e) {
                failures.add(id);
            }
        });
        return failures;
    }
}
