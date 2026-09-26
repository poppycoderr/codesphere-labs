package labs.excel;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.listener.PageReadListener;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 100 万行、10 列的 xlsx：generate 用 SXSSF 流式写出；poi 用 XSSFWorkbook 整体加载；fesod 用 PageReadListener 按批读取。
 * 每种读取方式在独立的 JVM 里运行，后台线程每 20ms 采样一次堆使用量，输出采样到的最大值。
 */
public class ExcelMemory {

    /** 一行订单，字段顺序与表格列顺序一致。 */
    public static class OrderRow {
        private Long id;
        private String orderNo;
        private Long userId;
        private String sku;
        private Integer quantity;
        private String price;
        private String city;
        private String status;
        private String createdAt;
        private String remark;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    static final String[] HEADERS = {"id", "orderNo", "userId", "sku", "quantity", "price", "city", "status", "createdAt", "remark"};
    static final String[] CITIES = {"Hangzhou", "Shanghai", "Beijing", "Shenzhen"};
    static final String[] STATUS = {"CREATED", "PAID", "SHIPPED"};

    public static void main(String[] args) throws Exception {
        File file = new File(args[1]);
        int rows = args.length > 2 ? Integer.parseInt(args[2]) : 1_000_000;
        switch (args[0]) {
            case "generate" -> generate(file, rows);
            case "poi" -> measure("poi", () -> {
                try (var wb = new XSSFWorkbook(file)) {
                    return wb.getSheetAt(0).getLastRowNum();
                }
            });
            case "fesod" -> measure("fesod", () -> {
                AtomicLong count = new AtomicLong();
                FesodSheet.read(file, OrderRow.class, new PageReadListener<OrderRow>(batch -> count.addAndGet(batch.size()), 1000))
                        .sheet().doRead();
                return count.get();
            });
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    interface Job {
        long run() throws Exception;
    }

    static void measure(String name, Job job) throws Exception {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        AtomicLong peak = new AtomicLong();
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        long t0 = System.nanoTime();
        String result;
        try {
            result = "读到数据行 " + job.run();
        } catch (OutOfMemoryError e) {
            result = "OutOfMemoryError";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        sampler.interrupt();
        long maxHeap = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        System.out.printf("%s\t-Xmx 约 %d MB：%s，堆使用峰值约 %d MB，耗时 %d ms%n", name, maxHeap, result, peak.get() / 1024 / 1024, ms);
    }

    static void generate(File file, int rows) throws Exception {
        try (var wb = new SXSSFWorkbook(1000)) {
            Sheet sheet = wb.createSheet("orders");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) header.createCell(c).setCellValue(HEADERS[c]);
            java.util.Random random = new java.util.Random(7);
            for (int i = 1; i <= rows; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue(i);
                r.createCell(1).setCellValue(String.format("ORD-%010d", random.nextInt(1_000_000_000)));
                r.createCell(2).setCellValue(random.nextInt(5_000_000));
                r.createCell(3).setCellValue("SKU-" + random.nextInt(500));
                r.createCell(4).setCellValue(1 + random.nextInt(5));
                r.createCell(5).setCellValue(String.format("%d.%02d", random.nextInt(1000), random.nextInt(100)));
                r.createCell(6).setCellValue(CITIES[random.nextInt(CITIES.length)]);
                r.createCell(7).setCellValue(STATUS[random.nextInt(STATUS.length)]);
                r.createCell(8).setCellValue("2026-09-" + String.format("%02d", 1 + random.nextInt(27)) + " 10:00:00");
                r.createCell(9).setCellValue("remark-" + random.nextInt(1000));
            }
            try (var out = new java.io.FileOutputStream(file)) {
                wb.write(out);
            }
        }
        System.out.printf("generate\t%d 行、%d 列，文件 %.1f MB%n", rows, HEADERS.length, file.length() / 1048576.0);
    }
}
