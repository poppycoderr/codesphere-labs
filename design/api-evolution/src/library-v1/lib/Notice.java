package lib;
public record Notice(String to, String subject, String body) {
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String to, subject, body;
        public Builder to(String v) { to = v; return this; }
        public Builder subject(String v) { subject = v; return this; }
        public Builder body(String v) { body = v; return this; }
        public Notice build() { return new Notice(to, subject, body); }
    }
}
