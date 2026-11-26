"""Summarize OCR and rubric evidence quality for consented, writer-disjoint JSONL data."""
import argparse
import json
import statistics
import unicodedata
from collections import Counter
from pathlib import Path


def normalized(text):
    return unicodedata.normalize("NFC", text)


def edit_distance(reference, observed):
    previous = list(range(len(observed) + 1))
    for i, expected in enumerate(reference, start=1):
        current = [i]
        for j, actual in enumerate(observed, start=1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (expected != actual)))
        previous = current
    return previous[-1]


def divide(numerator, denominator):
    return round(numerator / denominator, 4) if denominator else None


def mean(values):
    return round(statistics.mean(values), 3) if values else None


def evaluate(rows):
    counts = Counter()
    times, manual_times = [], []
    for row in rows:
        reference = normalized(row["reference_transcription"])
        observed = normalized(row["ocr_transcription"])
        counts["edits"] += edit_distance(reference, observed)
        counts["characters"] += len(reference)
        fields = row.get("reference_fields", {})
        extracted = row.get("ocr_fields", {})
        correct_fields = sum(
            key in extracted and normalized(value) == normalized(extracted[key]) for key, value in fields.items()
        )
        counts.update(fields=len(fields), extracted_fields=len(extracted), correct_fields=correct_fields,
                      missing_fields=len(set(fields) - set(extracted)))
        required = set(row.get("reference_evidence", []))
        linked = set(row.get("linked_evidence", []))
        counts.update(required=len(required), linked=len(required & linked))
        counts["abstained"] += row["abstained"]
        if "review_corrected" in row:
            counts["reviewed"] += 1
            counts["corrected"] += row["review_corrected"]
        if "review_seconds" in row and "manual_seconds" in row:
            times.append(row["review_seconds"])
            manual_times.append(row["manual_seconds"])
    return {
        "scripts": len(rows), "writers": len({row["writer"] for row in rows}),
        "character_error_rate": divide(counts["edits"], counts["characters"]),
        "critical_field_exact_match_and_recall": divide(counts["correct_fields"], counts["fields"]),
        "critical_field_precision": divide(counts["correct_fields"], counts["extracted_fields"]),
        "missed_critical_content_rate": divide(counts["missing_fields"], counts["fields"]),
        "source_linked_evidence_coverage": divide(counts["linked"], counts["required"]),
        "abstention_rate": divide(counts["abstained"], len(rows)),
        "reviewer_correction_rate": divide(counts["corrected"], counts["reviewed"]),
        "mean_assisted_review_seconds": mean(times), "mean_manual_review_seconds": mean(manual_times)
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.jsonl.read_text(encoding="utf-8").splitlines() if line.strip()]
    splits = {}
    for row in rows:
        writer, split = row["writer"], row["split"]
        if split not in {"train", "validation", "test"} or splits.setdefault(writer, split) != split:
            parser.error("Use exactly one train, validation or test split per writer")
    test = [row for row in rows if row["split"] == "test"]
    if not test:
        parser.error("A held-out test split is required")
    print(json.dumps(evaluate(test), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
