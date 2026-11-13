// Renders the 1024×1024 app icon:  swift tools/make_icon.swift Unroll/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import AppKit
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "icon-1024.png"
let S: CGFloat = 1024
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: Int(S), height: Int(S), bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
// Flat black, white mark: matches the app's monochrome UI.
ctx.setFillColor(CGColor(gray: 0.04, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: S, height: S))
let ink = CGColor(gray: 0.96, alpha: 1)
// Film roll "unrolling" into frames.
ctx.setFillColor(ink)
ctx.fillEllipse(in: CGRect(x: 170, y: 380, width: 300, height: 300))
ctx.setFillColor(CGColor(gray: 0.04, alpha: 1))
ctx.fillEllipse(in: CGRect(x: 265, y: 475, width: 110, height: 110))
ctx.setFillColor(ink)
let strip = CGRect(x: 320, y: 380, width: 560, height: 170)
ctx.fill(strip)
ctx.setFillColor(CGColor(gray: 0.04, alpha: 1))
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
