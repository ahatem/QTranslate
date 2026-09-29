import AppKit
import Foundation

var command = "languages"
var inputPath = ""
var outputPath = ""
var language = ""
let args = CommandLine.arguments
var index = 1
while index + 1 < args.count {
    switch args[index] {
    case "--command": command = args[index + 1]
    case "--input": inputPath = args[index + 1]
    case "--output": outputPath = args[index + 1]
    case "--language": language = args[index + 1]
    default: break
    }
    index += 2
}

func emit(_ value: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: value), !outputPath.isEmpty else { exit(2) }
    do { try data.write(to: URL(fileURLWithPath: outputPath)) } catch { exit(2) }
}

let checker = NSSpellChecker.shared
let available = checker.availableLanguages
if command == "languages" {
    emit(["ok": true, "languages": available])
    exit(0)
}
guard command == "check", !language.isEmpty, available.contains(language),
      let text = try? String(contentsOfFile: inputPath, encoding: .utf8) else {
    emit(["ok": false])
    exit(0)
}

let source = text as NSString
var findings: [[String: Any]] = []
var offset = 0
while offset < source.length {
    let range = checker.checkSpelling(of: text, startingAt: offset, language: language,
                                      wrap: false, inSpellDocumentWithTag: 0, wordCount: nil)
    if range.location == NSNotFound { break }
    if range.length == 0 || range.location < offset || NSMaxRange(range) > source.length {
        emit(["ok": false])
        exit(0)
    }
    let guesses = checker.guesses(forWordRange: range, in: text, language: language,
                                  inSpellDocumentWithTag: 0) ?? []
    findings.append(["start": range.location, "length": range.length,
                     "original": source.substring(with: range), "suggestions": guesses])
    offset = NSMaxRange(range)
}
emit(["ok": true, "findings": findings])
