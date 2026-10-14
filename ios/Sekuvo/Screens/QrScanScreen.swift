import AVFoundation
import SekuvoCore
import SwiftUI
import UIKit

/// QR ile içe aktarma — Android'deki `QrScanScreen.kt`.
///
/// Kamera görüntüsü cihazda çözülür (AVFoundation); kareler sırasız toplanır,
/// küme tamamlanınca aktarım parolası istenir. Görüntü hiçbir yere gitmez ve
/// saklanmaz. `onEnvelope` verilirse zarf çağırana döner ve içe aktarma
/// açılmaz (düzenleme ekranında "Parolayı QR ile güncelle").
struct QrScanScreen: View {
    var onEnvelope: ((Data) -> Void)? = nil
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var toplayici = QrCollector()
    @State private var durum = L("qr_status_aim")
    @State private var alinan = 0
    @State private var toplam = 0
    @State private var okundu = false
    @State private var izin = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var zarf: Zarf?
    @State private var teslimEdildi = false

    struct Zarf: Identifiable {
        let id = UUID()
        let data: Data
    }

    private var kameraVar: Bool { KameraDenetci.cihaz() != nil }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                if izin == .authorized, kameraVar {
                    KameraOnizleme { metin in yeniMetin(metin) }
                    Vizor(okundu: okundu)
                } else {
                    VStack(spacing: 14) {
                        Image(systemName: kameraVar ? "camera.fill" : "camera.badge.ellipsis")
                            .font(.system(size: 40)).foregroundStyle(.white.opacity(0.8))
                        Text(L(kameraVar ? "qr_permission_rationale" : "ios_camera_unavailable"))
                            .multilineTextAlignment(.center).foregroundStyle(.white)
                        if kameraVar {
                            Button(L(izin == .notDetermined ? "qr_grant_permission" : "qr_grant_in_settings"), action: izinIste)
                                .buttonStyle(FilledButton()).frame(maxWidth: 280, minHeight: 44)
                        }
                    }
                    .padding(32)
                }
            }
            .ignoresSafeArea(edges: .top)

            VStack(alignment: .leading, spacing: 6) {
                Text(durum).font(.headline).foregroundStyle(Theme.onSurface)
                if toplam > 1 {
                    ProgressView(value: Double(alinan), total: Double(toplam)).tint(Theme.primary)
                    let eksik = toplayici.missing
                    if !eksik.isEmpty {
                        Text(L("qr_missing_frames", eksik.map(String.init).joined(separator: ", ")))
                            .font(.caption).foregroundStyle(Theme.onSurfaceVariant)
                    }
                }
                Button(L("qr_cancel")) { kapat() }
                    .buttonStyle(FilledButton())
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .padding(.top, 8)
            }
            .padding(16)
            .background(Theme.background)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: alinan)
        .sensoryFeedback(.success, trigger: okundu) { _, yeni in yeni }
        .sheet(item: $zarf, onDismiss: {
            // Parola penceresi vazgeçilerek kapandıysa yeniden taramaya dön.
            if kasa.toast == nil { okundu = false; durum = L("qr_status_aim") }
        }) { z in
            NavigationStack { ImportSheet(source: .qr, envelope: z.data) { kapat() } }
        }
        .task {
            if izin == .notDetermined, kameraVar { izinIste() }
            #if DEBUG
            if !kameraVar || ProcessInfo.processInfo.arguments.contains("-ornekQr") { await benzet() }
            #endif
        }
    }

    private func yeniMetin(_ metin: String) {
        guard zarf == nil, !teslimEdildi else { return }
        switch toplayici.add(metin) {
        case .ignored: break
        case .progress(let n, let t):
            alinan = n; toplam = t
            durum = L("qr_status_received", n, t)
        case .complete(let e):
            alinan = toplam; okundu = true
            durum = L("qr_status_done")
            if let onEnvelope {
                teslimEdildi = true
                onEnvelope(Data(e.utf8))
                kapat()
            } else {
                zarf = Zarf(data: Data(e.utf8))
            }
        case .mismatch:
            alinan = 0; toplam = 0
            durum = L("qr_status_frames_mismatch")
        }
    }

    private func izinIste() {
        if izin == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { verildi in
                Task { @MainActor in izin = verildi ? .authorized : .denied }
            }
        } else if let ayarlar = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(ayarlar)
        }
    }

    #if DEBUG
    /// Simülatörde kamera yok: `aktar.html`'in üreteceği türden bir zarfın
    /// karelerini sırasız ve bir tekrarla besler (yalnız Debug).
    private func benzet() async {
        var veri = EntryData()
        let kayit: DecryptedEntry
        if onEnvelope != nil {
            // Parola güncelleme: aktar.html'in "yeni parola" zarfı gibi.
            veri.password = PasswordGenerator.generate()
            kayit = DecryptedEntry(type: .login, title: "Yeni parola", data: veri,
                                   createdAt: 1_760_500_000_000, updatedAt: 1_760_500_000_000)
        } else {
            veri.notes = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + (0..<60).map { _ in
                Crypto.randomBytes(52).base64EncodedString()
            }.joined(separator: "\n") + "\n-----END OPENSSH PRIVATE KEY-----"
            kayit = DecryptedEntry(type: .note, title: "Sunucu SSH (staging)", data: veri,
                                   createdAt: 1_760_500_000_000, updatedAt: 1_760_500_000_000, noteKind: .anahtar)
        }
        guard let zarfVeri = try? Backup.export(password: "ornek-aktarim-2026", entries: [kayit]) else { return }
        var kareler = QrTransfer.frames(for: String(decoding: zarfVeri, as: UTF8.self)).shuffled()
        if let ilk = kareler.first { kareler.insert(ilk, at: min(2, kareler.count)) }
        izin = .authorized
        for kare in kareler {
            try? await Task.sleep(for: .milliseconds(700))
            yeniMetin(kare)
        }
    }
    #endif
}

/// Köşe çizgili vizör; okununca yeşile döner.
private struct Vizor: View {
    let okundu: Bool

    var body: some View {
        Koseler()
            .stroke(okundu ? Color.green : Color.white.opacity(0.92), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
            .frame(width: 260, height: 260)
            .animation(.easeOut(duration: 0.2), value: okundu)
    }

    private struct Koseler: Shape {
        func path(in r: CGRect) -> Path {
            var p = Path()
            let l: CGFloat = 38
            for (kose, yatay, dikey) in [(CGPoint(x: r.minX, y: r.minY), CGFloat(1), CGFloat(1)),
                                         (CGPoint(x: r.maxX, y: r.minY), -1, 1),
                                         (CGPoint(x: r.maxX, y: r.maxY), -1, -1),
                                         (CGPoint(x: r.minX, y: r.maxY), 1, -1)] {
                p.move(to: CGPoint(x: kose.x, y: kose.y + dikey * l))
                p.addLine(to: kose)
                p.addLine(to: CGPoint(x: kose.x + yatay * l, y: kose.y))
            }
            return p
        }
    }
}

/// AVFoundation önizlemesi ve QR okuyucu.
private struct KameraOnizleme: UIViewRepresentable {
    let onCode: @MainActor (String) -> Void

    func makeUIView(context: Context) -> OnizlemeView {
        let v = OnizlemeView()
        v.denetci.onCode = onCode
        v.onizleme.session = v.denetci.session
        v.onizleme.videoGravity = .resizeAspectFill
        v.denetci.baslat()
        return v
    }

    func updateUIView(_ v: OnizlemeView, context: Context) { v.denetci.onCode = onCode }

    static func dismantleUIView(_ v: OnizlemeView, coordinator: ()) { v.denetci.durdur() }

    final class OnizlemeView: UIView {
        let denetci = KameraDenetci()
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var onizleme: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

/// Kamera oturumu kendi kuyruğunda çalışır; QR metinleri ana iş parçacığına gelir.
final class KameraDenetci: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let kuyruk = DispatchQueue(label: "sekuvo.kamera")
    @MainActor var onCode: ((String) -> Void)?

    /// Yakın çekimde odak tutsun diye sanal (çok mercekli) kamera tercih edilir:
    /// Pro modellerde kod yaklaşınca sistem kendiliğinden makroya geçer.
    static func cihaz() -> AVCaptureDevice? {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera],
            mediaType: .video, position: .back
        ).devices.first
    }

    func baslat() {
        kuyruk.async { [self] in
            guard let cihaz = Self.cihaz(), let girdi = try? AVCaptureDeviceInput(device: cihaz) else { return }
            session.beginConfiguration()
            // Yoğun kareler (sürüm 25 civarı QR) için yüksek çözünürlük.
            if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
            if session.canAddInput(girdi) { session.addInput(girdi) }
            let cikti = AVCaptureMetadataOutput()
            if session.canAddOutput(cikti) {
                session.addOutput(cikti)
                cikti.setMetadataObjectsDelegate(self, queue: .main)
                cikti.metadataObjectTypes = [.qr]
            }
            session.commitConfiguration()
            yakinOdakIcinYakinlas(cihaz)
            session.startRunning()
        }
    }

    func durdur() { kuyruk.async { [self] in session.stopRunning() } }

    /// Apple'ın barkod örneğindeki yöntem: kod, kameranın en yakın odak
    /// mesafesinden daha yakına tutulmak zorunda kalmasın diye yakınlaştır.
    private func yakinOdakIcinYakinlas(_ cihaz: AVCaptureDevice) {
        let enYakin = Float(cihaz.minimumFocusDistance)   // mm; bilinmiyorsa -1
        guard enYakin > 0 else { return }
        let gorus = cihaz.activeFormat.videoFieldOfView * .pi / 180
        let kodBoyu: Float = 80          // ekranda gösterilen QR'ın yaklaşık genişliği (mm)
        let doluluk: Float = 0.6         // kodun görüntü genişliğinde kapladığı pay
        let mesafe = (kodBoyu / doluluk) / tan(gorus / 2)
        guard mesafe < enYakin else { return }
        let carpan = min(CGFloat(enYakin / mesafe), cihaz.activeFormat.videoMaxZoomFactor)
        if (try? cihaz.lockForConfiguration()) != nil {
            cihaz.videoZoomFactor = carpan
            cihaz.unlockForConfiguration()
        }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput nesneler: [AVMetadataObject],
                                    from connection: AVCaptureConnection) {
        let metinler = nesneler.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        MainActor.assumeIsolated {
            for m in metinler { onCode?(m) }
        }
    }
}
