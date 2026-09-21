import lib.Notice;
public class Client {
    public static void main(String[] a) {
        Notice viaBuilder = Notice.builder().to("ops").subject("disk").body("90%").build();
        System.out.println("builder 调用方: " + viaBuilder);
        Notice viaCtor = new Notice("ops", "disk", "90%");
        System.out.println("构造器调用方: " + viaCtor);
    }
}
