import org.mapstruct.Mapper;

/** 目标对象比源对象多一个字段；unmappedTargetPolicy=ERROR 时编译应当失败。 */
@Mapper
public interface BadMapper {
    class Source {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    class Target {
        private String name;
        private String nickname;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
    }

    Target map(Source source);
}
