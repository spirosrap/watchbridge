import SwiftUI
import QRCodeGenerator

/// Draws a QR code with SwiftUI Canvas (Core Image is not available on watchOS).
struct QRCodeView: View {
    let text: String

    private var code: QRCode? {
        try? QRCode.encode(text: text, ecl: .low)
    }

    var body: some View {
        Canvas { ctx, size in
            guard let qr = code else { return }
            let quiet = 1
            let n = qr.size + quiet * 2
            let cell = min(size.width, size.height) / CGFloat(n)
            let ox = (size.width - cell * CGFloat(n)) / 2
            let oy = (size.height - cell * CGFloat(n)) / 2
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white))
            for y in 0..<qr.size {
                for x in 0..<qr.size where qr.getModule(x: x, y: y) {
                    let r = CGRect(x: ox + cell * CGFloat(x + quiet),
                                   y: oy + cell * CGFloat(y + quiet),
                                   width: cell + 0.5, height: cell + 0.5)
                    ctx.fill(Path(r), with: .color(.black))
                }
            }
        }
        .accessibilityLabel("Pairing QR code")
    }
}
