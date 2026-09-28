package labs.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** MapStruct 在编译期生成 UserMapperImpl。 */
@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    UserDto toDto(UserEntity entity);
}
