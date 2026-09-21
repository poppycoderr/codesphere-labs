package lib;
public record Notice(String to, String subject, String body, int priority) {
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String to, subject, body; private int priority = 3;
        public Builder to(String v) { to = v; return this; }
        public Builder subject(String v) { subject = v; return this; }
        public Builder body(String v) { body = v; return this; }
        public Builder priority(int v) { priority = v; return this; }
        public Notice build() { return new Notice(to, subject, body, priority); }
    }
}
