package labs.mapping;

import java.time.LocalDate;

/** 映射的目标对象，字段与 UserEntity 一一对应。 */
public class UserDto {
    private Long id;
    private String name;
    private String email;
    private String phone;
    private String city;
    private Integer level;
    private LocalDate birthday;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
