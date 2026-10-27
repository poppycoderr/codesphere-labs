package labs.txattr;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 简单实体，用来观察只读事务里的脏检查。 */
@Entity
public class Item {
    @Id
    private Long id;
    private String name;

    protected Item() {
    }

    public Item(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
