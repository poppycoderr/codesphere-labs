package labs.mapping;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.springframework.beans.BeanUtils;

/** 同一个 8 字段对象的三种映射：手写 setter、MapStruct、Spring BeanUtils.copyProperties。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MappingBench {
    UserEntity entity;

    @Setup
    public void setup() {
        entity = new UserEntity();
        entity.setId(42L);
        entity.setName("alice");
        entity.setEmail("alice@example.com");
        entity.setPhone("000-0000");
        entity.setCity("somewhere");
        entity.setLevel(3);
        entity.setBirthday(LocalDate.of(2000, 1, 1));
        entity.setVersion(7L);
    }

    @Benchmark
    public UserDto handwritten() {
        UserDto d = new UserDto();
        d.setId(entity.getId());
        d.setName(entity.getName());
        d.setEmail(entity.getEmail());
        d.setPhone(entity.getPhone());
        d.setCity(entity.getCity());
        d.setLevel(entity.getLevel());
        d.setBirthday(entity.getBirthday());
        d.setVersion(entity.getVersion());
        return d;
    }

    @Benchmark
    public UserDto mapstruct() {
        return UserMapper.INSTANCE.toDto(entity);
    }

    @Benchmark
    public UserDto beanUtils() {
        UserDto d = new UserDto();
        BeanUtils.copyProperties(entity, d);
        return d;
    }
}
