# ỨNG DỤNG ĐỒNG BỘ TỆP TIN (CLIENT - SERVER FILE SYNCHRONIZATION)

> **Môn học**: Lập trình Mạng / Đồ án Cơ sở (DOANCS)  
> **Ngôn ngữ**: Java (JDK 22, Apache Maven / Apache NetBeans 22)  
> **Giao thức**: TCP Sockets (`ServerSocket` & `Socket`) kết hợp Gói tin `Packet` (`Serializable`)  
> **Cơ sở dữ liệu**: Embedded SQLite (`sqlite-jdbc` 3.45.3) lưu trữ Metadata, Users, Sessions, File Versions, Recycle Bin & Conflict Logs  
> **Kỹ thuật tối ưu**: Java 22 `Virtual Threads (Project Loom)` + Nén động `GZIP On-the-fly` + Java NIO `WatchService` + Mã băm `SHA-256` + `Chunking 64KB` + `TCP Push Notification`  
> **Giao diện**: Java Swing với FlatLaf Modern UI (Dark / Light Theme, System Tray, Live Search & Filter, Interactive Conflict Dialog, Versioning Rollback Dialog)

---

## 1. PHÂN RÃ CẤU TRÚC DỰ ÁN (PROJECT STRUCTURE)

Dự án được phân rã chuẩn theo mô hình kiến trúc Client - Server phân lớp cho đồ án Lập trình Mạng:

```
DOANCS/
├── pom.xml                                    # Cấu hình Maven (FlatLaf, SQLite JDBC, Maven Shade Fat JAR)
├── Run_App.bat                                # Khởi chạy Dashboard chính
├── Run_Server.bat                             # Khởi chạy riêng Server
├── Run_Client.bat                             # Khởi chạy riêng Client
├── Run_Quick_Test.bat                         # Chạy bài kiểm thử tự động toàn diện (9 bước)
├── README.md                                  # Tài liệu hướng dẫn đồ án
└── src/
    ├── main/java/com/mycompany/doancs/
    │   ├── DOANCS.java                        # Entry point chính của ứng dụng
    │   │
    │   ├── common/                            # [GÓI GIAO TIẾP CHUNG - DÙNG CHO CẢ CLIENT & SERVER]
    │   │   ├── Packet.java                    # Đối tượng gói tin tuần tự hóa (Serializable): GZIP flag, offset, opcode, metadata
    │   │   ├── CommandType.java               # Enum các mã lệnh (AUTH, UPLOAD, DOWNLOAD, GET_FILE_VERSIONS, RESTORE_FILE_VERSION,...)
    │   │   ├── FileMetadata.java              # Đối tượng siêu dữ liệu tệp (đường dẫn, kích thước, timestamp, SHA-256)
    │   │   └── HashUtils.java                 # Tiện ích băm nội dung tệp bằng thuật toán SHA-256
    │   │
    │   ├── model/                             # [GÓI MÔ HÌNH DỮ LIỆU]
    │   │   ├── FileVersionInfo.java           # Mô hình lưu trữ thông tin phiên bản lịch sử tệp (Versioning)
    │   │   ├── ClientSessionInfo.java         # Thông tin phiên kết nối máy khách
    │   │   ├── SyncItem.java / SyncAction.java# Mục so sánh trạng thái đồng bộ
    │   │   └── SyncStats.java                 # Thống kê băng thông, tốc độ truyền thực tế & ETA
    │   │
    │   ├── server/                            # [GÓI MÁY CHỦ - PHÍA SERVER]
    │   │   ├── ServerCore.java                # Server Core: Java 22 Virtual Threads (Loom), quản lý cổng mạng & Push notification
    │   │   ├── ClientHandler.java             # Xử lý kết nối client trên Virtual Thread riêng biệt, xác thực, streaming, versioning
    │   │   ├── StorageManager.java            # Quản lý không gian lưu trữ trên đĩa, nén/giải nén GZIP, sao lưu phiên bản, thùng rác
    │   │   ├── ServerEventListener.java       # Interface lắng nghe sự kiện Server cập nhật lên GUI
    │   │   └── db/
    │   │       └── DatabaseManager.java       # Quản trị SQLite: Bảng users, sessions, files_metadata, file_versions, recycle_bin, conflict_logs
    │   │
    │   ├── client/                            # [GÓI MÁY TRẠM - PHÍA CLIENT]
    │   │   ├── NetworkClient.java             # Network Client: Kết nối, nén động GZIP, kiểm tra SHA-256, kênh push listener
    │   │   ├── FileWatcher.java               # File Watcher: Giám sát thư mục thời gian thực bằng Java NIO WatchService
    │   │   └── ClientEventListener.java       # Interface lắng nghe sự kiện Client cập nhật lên GUI
    │   │
    │   ├── gui/                               # [GÓI GIAO DIỆN ĐỒ HỌA SWING HIỆN ĐẠI]
    │   │   ├── MainDashboard.java             # Cửa sổ bảng điều khiển trung tâm
    │   │   ├── ServerFrame.java               # Cửa sổ Server (Quản lý tệp, tìm kiếm, lịch sử phiên bản, thùng rác, máy khách)
    │   │   ├── ClientFrame.java               # Cửa sổ Client (System Tray, tìm kiếm, lọc trạng thái, giải quyết xung đột tương tác)
    │   │   ├── ConflictResolutionDialog.java  # Hộp thoại giải quyết xung đột trực quan (Giữ bản Client, Giữ Server, Giữ cả 2)
    │   │   ├── FileVersionDialog.java         # Hộp thoại xem lịch sử các phiên bản cũ và Khôi phục (Rollback)
    │   │   └── components/
    │   │       └── ModernUI.java              # Thư viện component thẩm mỹ cao (Cards, Badges, Table Renderers)
    │   │
    │   └── util/                              # [GÓI TIỆN ÍCH HỖ TRỢ]
    │       ├── FileUtils.java                 # Thao tác tệp, chuẩn hóa đường dẫn, tạo tên file xung đột
    │       ├── SyncIgnoreFilter.java          # Bộ lọc tệp loại trừ (.syncignore)
    │       ├── ThemeManager.java              # Quản lý chuyển đổi giao diện Sáng / Tối (FlatLaf)
    │       └── SampleDataGenerator.java       # Bộ sinh dữ liệu kiểm thử
    └── test/java/com/mycompany/doancs/
        ├── SyncIntegrationTest.java          # Bài kiểm thử cơ chế truyền tệp Socket và Incremental Sync
        └── AdvancedFeaturesIntegrationTest.java # Bài kiểm thử toàn diện 9 bước: Auth, SQLite, Push, Conflict, Recycle Bin, Versioning Rollback, GZIP
```

---

## 2. CÁC CƠ CHẾ NÂNG CẤP ĐỘT PHÁ ĐÃ ĐƯỢC GIẢI QUYẾT

### Nhóm 1: Tính Năng Mạng "Ăn Điểm Tuyệt Đối" (Networking & Concurrency)
1. **Java 22 Virtual Threads (Project Loom)**:
   - Server tận dụng `Executors.newVirtualThreadPerTaskExecutor()`.
   - Mỗi kết nối từ client được phục vụ bởi một Virtual Thread siêu nhẹ, giúp máy chủ xử lý đồng thời hàng nghìn client mà không làm cạn kiệt tài nguyên hệ điều hành.
2. **Nén Động GZIP trên đường truyền (On-the-fly Dynamic Compression)**:
   - Tự động nhận diện các tệp văn bản/dữ liệu/code (`.txt`, `.java`, `.json`, `.csv`, `.docx`,...).
   - Nén trước khi truyền và giải nén ngay khi nhận trên Socket, tiết kiệm **50% - 85% băng thông mạng**.
   - Kiểm tra mã băm SHA-256 trên nội dung giải nén để đảm bảo toàn vẹn dữ liệu tuyệt đối.
3. **Cơ chế Truyền Tiếp (Resumable Transfer Support)**:
   - Hỗ trợ kiểm tra offset byte để tiếp tục truyền tệp khi kết nối bị ngắt quãng giữa chừng.

### Nhóm 2: Cơ Chế Đồng Bộ Chuyên Nghiệp (Dropbox / Google Drive style)
1. **Lịch sử Phiên bản Tệp & Khôi phục (File Versioning & Rollback)**:
   - Mỗi lần một tệp bị chỉnh sửa và ghi đè trên Server, phiên bản trước đó tự động được lưu trữ vào kho lưu trữ `.versions/` và chỉ mục vào bảng SQLite `file_versions`.
   - Người dùng click chuột phải vào bất kỳ tệp nào $\rightarrow$ **"Xem lịch sử phiên bản (Versioning)..."** $\rightarrow$ Xem danh sách các bản cũ (v1, v2,...) và bấm **"Khôi phục phiên bản này (Rollback)"**.
2. **Thùng Rác An Toàn (Recycle Bin / Soft Delete)**:
   - Khi xóa tệp, hệ thống chuyển tệp vào kho thùng rác `.trash/` và ghi nhận vào bảng `recycle_bin`.
   - Người quản trị hoặc người dùng có thể dễ dàng khôi phục lại tệp bị xóa nhầm với 1 click.
3. **Bộ lọc tệp loại trừ `.syncignore`**:
   - Tự động tạo và đọc tệp cấu hình `.syncignore` để bỏ qua các tệp tạm (`*.tmp`, `*~`), thư mục build (`target/`, `node_modules/`, `.git/`), tránh lãng phí dung lượng truyền tải.
4. **Hộp thoại Giải Quyết Xung Đột Tương Tác (Interactive Conflict Resolution Dialog)**:
   - Khi phát hiện tệp bị sửa đổi ở cả 2 phía cùng lúc, ứng dụng bật hộp thoại trực quan cho người dùng chọn:
     - 📌 **Giữ bản của tôi** (Ghi đè Server).
     - 📌 **Giữ bản trên Server** (Ghi đè Client).
     - 📌 **Bảo toàn cả hai bản** (Lưu thêm bản sao `_conflict_...` - không mất dữ liệu).
     - Hỗ trợ tùy chọn *"Áp dụng cho tất cả các xung đột còn lại"*.

### Nhóm 3: Trải Nghiệm Người Dùng (UI/UX)
1. **Chạy Ngầm Khay Hệ Thống (System Tray) & Windows Native Notifications**:
   - Khi thu nhỏ cửa sổ, ứng dụng tự động ẩn xuống System Tray cạnh đồng hồ Windows.
   - Khi máy khác tải file lên hoặc sửa đổi, Server đẩy thông báo về, Client sẽ hiện bong bóng thông báo Windows (System Notification) kèm âm thanh.
2. **Thanh Tìm Kiếm & Lọc Trạng Thái Thời Gian Thực (Live Search & Filter Bar)**:
   - Bổ sung ô tìm kiếm tệp và dropdown lọc trạng thái (`UPLOAD`, `DOWNLOAD`, `UP_TO_DATE`, `CONFLICT`) trên cả ClientFrame và ServerFrame.
3. **Đồng Hồ Đo Tốc Độ Băng Thông Thực Tế**:
   - Hiển thị trực tiếp tốc độ mạng (ví dụ: `15.4 MB/s`) và ước tính thời gian hoàn tất (ETA) kèm tiến trình tổng thể.

---

## 3. HƯỚNG DẪN KHỞI CHẠY & KIỂM THỬ

### Cách 1: Chạy bằng file Batch trên Windows (Khuyên dùng)
- [`Run_App.bat`](file:///c:/Users/huyle/Downloads/DOANCS/Run_App.bat): Mở Dashboard điều khiển trung tâm (có nút mở Server, Client hoặc mở cả hai cùng lúc).
- [`Run_Quick_Test.bat`](file:///c:/Users/huyle/Downloads/DOANCS/Run_Quick_Test.bat): Chạy bài kiểm thử tự động toàn diện qua console (Xác thực SQLite, Virtual Threads, Push Event, Xử lý Xung đột, Thùng rác, Lịch sử Phiên bản, Nén GZIP).
- [`Run_Server.bat`](file:///c:/Users/huyle/Downloads/DOANCS/Run_Server.bat) / [`Run_Client.bat`](file:///c:/Users/huyle/Downloads/DOANCS/Run_Client.bat): Khởi chạy riêng Server hoặc Client.

### Cách 2: Mở và chạy trên NetBeans 22
1. Mở NetBeans $\rightarrow$ **File** $\rightarrow$ **Open Project...** $\rightarrow$ Chọn thư mục `DOANCS`.
2. NetBeans sẽ nhận diện cấu trúc dự án Maven với các gói `common`, `server`, `client`, `gui`, `util`.
3. Nhấn **F6** (hoặc chuột phải vào Project $\rightarrow$ **Run**) để chạy ứng dụng.

---

## 4. CHIẾN LƯỢC PHÂN NHÁNH VÀ QUY TẮC COMMIT (GIT RULES)

Dự án tuân thủ nghiêm ngặt quy chế làm việc nhóm và bảo vệ nhánh:
- **Nhánh chính (`main`)**: Được bảo vệ (Protected Branch), bắt buộc tích hợp qua Pull Request / Merge Request và có ít nhất 1 thành viên review Approve.
- **Nhánh tính năng**: Đặt tên theo chuẩn `feature/<MSSV>-issue-<Số_Issue>-<Tên_ngắn_gọn>`.
- **Quy chuẩn thông điệp Commit**: Tuân thủ Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`).
- **Vệ sinh mã nguồn**: Sử dụng `.gitignore` đúng chuẩn Maven, nghiêm cấm commit `target/`, file `.jar`, `.class`, `.db`, `.idea/`.

---

## 5. THIẾT LẬP ĐỊNH DANH CÁ NHÂN (GIT CONFIG)

Mỗi thành viên trong nhóm cấu hình định danh Git cục bộ khớp danh sách lớp:
```bash
git config --global user.name "MSSV_HoVaTen"
git config --global user.email "email_sinh_vien@domain"
```

---

## 6. BẢNG MA TRẬN ĐÓNG GÓP CỦA CÁC THÀNH VIÊN (CONTRIBUTION MATRIX)

Bảng thống kê sự đóng góp của 4 thành viên đối chiếu khi chấm điểm và bảo vệ đồ án:

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| :---: | :--- | :--- | :---: |
| **2380600830** | **Lê Văn Huy** *(Trưởng nhóm)* | Xây dựng kiến trúc mạng TCP Socket, Gói tin `Packet`, `ServerCore` đa luồng với Java 22 Virtual Threads, `ClientHandler` và kênh `TCP Push Notification`. | `!1` (hoặc `#1`) |
| **2380602005** | **Nguyễn Nhật Tấn** | Xây dựng `NetworkClient` kết nối Socket, giám sát thư mục thời gian thực với Java NIO `WatchService`, băm toàn vẹn SHA-256 và bộ lọc `.syncignore`. | `!2` (hoặc `#2`) |
| **2380601641** | **Nguyễn Xuân Phát** | Thiết kế CSDL SQLite `DatabaseManager`, quản lý lưu trữ `StorageManager` phân mảnh Chunking 64KB (Resumable), Versioning Rollback, Recycle Bin và nén GZIP. | `!3` (hoặc `#3`) |
| **2380600139** | **Lê Huy Bảo** | Thiết kế giao diện Modern FlatLaf GUI (`MainDashboard`, `ServerFrame`, `ClientFrame`, System Tray Notification), hộp thoại Conflict/Versioning và bộ kiểm thử tự động. | `!4` (hoặc `#4`) |
