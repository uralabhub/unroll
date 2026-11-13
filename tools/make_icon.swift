// Renders the 1024×1024 app icon:  swift tools/make_icon.swift Unroll/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import AppKit
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "icon-1024.png"
let S: CGFloat = 1024
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: Int(S), height: Int(S), bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let bg = CGGradient(colorsSpace: cs, colors: [CGColor(red: 0.13, green: 0.11, blue: 0.10, alpha: 1), CGColor(red: 0.04, green: 0.04, blue: 0.045, alpha: 1)] as CFArray, locations: nil)!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: S), end: CGPoint(x: S, y: 0), options: [])
let amber = CGColor(red: 1, green: 0.66, blue: 0.25, alpha: 1)
// Film roll "unrolling" into frames.
ctx.setFillColor(amber)
ctx.fillEllipse(in: CGRect(x: 170, y: 380, width: 300, height: 300))
ctx.setFillColor(CGColor(red: 0.07, green: 0.06, blue: 0.06, alpha: 1))
ctx.fillEllipse(in: CGRect(x: 265, y: 475, width: 110, height: 110))
ctx.setFillColor(amber)
let strip = CGRect(x: 320, y: 380, width: 560, height: 170)
ctx.fill(strip)
ctx.setFillColor(CGColor(red: 0.07, green: 0.06, blue: 0.06, alpha: 1))
for i in 0..<3 {
    ctx.fill(CGRect(x: 500 + CGFloat(i) * 128, y: 412, width: 104, height: 106))
}
for i in 0..<9 {
    ctx.fill(CGRect(x: 482 + CGFloat(i) * 44, y: 386, width: 22, height: 14))
    ctx.fill(CGRect(x: 482 + CGFloat(i) * 44, y: 530, width: 22, height: 14))
}
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
print("wrote", out)
