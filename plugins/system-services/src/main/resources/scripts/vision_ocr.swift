// QTranslate System OCR - macOS backend helper.
//
// Runs Apple's Vision text recognition (VNRecognizeTextRequest) on device and writes the result as
// JSON. Nothing is uploaded and no network access is performed.
//
// Invoked as:
//     vision_ocr --command recognize|capabilities --image <path> --language <bcp47|''> --output <path>
//
// An empty --language asks Vision to detect the language (macOS 13+); older versions fail with
// unsupported_language rather than defaulting to English.
//
// The plugin's Gradle tasks compile this into one universal arm64 + x86_64 binary.

import Foundation
import CoreGraphics
import ImageIO
import Vision

let arguments = CommandLine.arguments
var command = "recognize"
var imagePath = ""
var language = ""
var outputPath = ""

var index = 1
while index < arguments.count {
    let key = arguments[index]
    func value() -> String {
        let next = index + 1
        return next < arguments.count ? arguments[next] : ""
    }
    switch key {
    case "--command":  command = value(); index += 2
    case "--image":    imagePath = value(); index += 2
    case "--language": language = value(); index += 2
    case "--output":   outputPath = value(); index += 2
    default:           index += 1
    }
}

func emit(_ payload: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
        exit(2)
    }
    if outputPath.isEmpty {
        FileHandle.standardOutput.write(data)
    } else {
        try? data.write(to: URL(fileURLWithPath: outputPath))
    }
}

func fail(_ category: String, _ message: String) -> Never {
    emit(["ok": false, "category": category, "error": message])
    exit(0)
}

/// Created the same way for both commands so they agree on defaults.
func makeRequest() -> VNRecognizeTextRequest {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true
    return request
}

/// Whether this macOS can detect a language on its own, reported so the plugin only advertises
/// auto-detection where it works.
func supportsLanguageDetection() -> Bool {
    if #available(macOS 13.0, *) {
        return true
    }
    return false
}

if command == "capabilities" {
    // supportedRecognitionLanguages() needs macOS 12, so an older system reports no languages
    // rather than guessing at any.
    var languages: [String] = []
    if #available(macOS 12.0, *) {
        do {
            languages = try makeRequest().supportedRecognitionLanguages()
        } catch {
            fail("missing_component", "Vision text recognition is unavailable: \(error.localizedDescription)")
        }
    }
    emit(["ok": true, "languages": languages, "autoDetect": supportsLanguageDetection()])
    exit(0)
}

guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: imagePath) as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fail("invalid_input", "The image '\(imagePath)' could not be decoded.")
}

let request = makeRequest()

if language.isEmpty {
    if #available(macOS 13.0, *) {
        request.automaticallyDetectsLanguage = true
    } else {
        fail("unsupported_language", "This version of macOS cannot detect the language of an image; choose a specific language.")
    }
} else {
    if #available(macOS 12.0, *) {
        let supported = (try? request.supportedRecognitionLanguages()) ?? []
        if !supported.contains(language) {
            fail("unsupported_language", "The OCR language '\(language)' is not supported by Vision.")
        }
    }
    request.recognitionLanguages = [language]
}

// Use the image's own EXIF orientation; assuming .up makes Vision read rotated text as noise.
var orientation = CGImagePropertyOrientation.up
if let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
   let raw = (properties[kCGImagePropertyOrientation] as? NSNumber)?.uint32Value,
   let parsed = CGImagePropertyOrientation(rawValue: raw) {
    orientation = parsed
}

let handler = VNImageRequestHandler(cgImage: image, orientation: orientation, options: [:])
do {
    try handler.perform([request])
} catch {
    fail("recognition_failed", error.localizedDescription)
}

guard let observations = request.results else {
    fail("recognition_failed", "Vision returned no recognition results.")
}

// Vision gives observations in no guaranteed order. Sort top to bottom (bounding boxes use a
// bottom-left origin, so a larger midY is higher) and then left to right.
let ordered = observations.sorted { lhs, rhs in
    let verticalDelta = abs(lhs.boundingBox.midY - rhs.boundingBox.midY)
    if verticalDelta > 0.02 {
        return lhs.boundingBox.midY > rhs.boundingBox.midY
    }
    return lhs.boundingBox.minX < rhs.boundingBox.minX
}

let lines = ordered.compactMap { $0.topCandidates(1).first?.string }
emit(["ok": true, "text": lines.joined(separator: "\n")])
exit(0)
