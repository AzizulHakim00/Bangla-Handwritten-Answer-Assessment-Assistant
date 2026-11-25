# Bangla Handwritten Answer Assessment Assistant

Spring Boot 3.5 / Java 21, Thymeleaf, MongoDB and Docker. The lavender academic interface now uses authenticated server APIs rather than browser demo data.

## Start locally

1. Install Docker Desktop.
2. Copy `.env.example` to `.env`.
3. Set three **different** passwords, each at least 12 characters.
4. Run `docker compose up --build`.
5. Open http://localhost:8085/login.

| Account | Responsibility |
| --- | --- |
| teacher@school.local | Create exams, save rubrics, upload scripts and review |
| marker@school.local | Transcribe and mark uploaded answers |
| moderator@school.local | Independently approve submitted marks |

Passwords come from `TEACHER_PASSWORD`, `MARKER_PASSWORD`, `MODERATOR_PASSWORD`. Accounts are deployment-configured and password hashes live in memory; this MVP has no public registration or password recovery. Sign out from Profile before switching accounts. Compose binds the app to localhost; neither MongoDB nor original documents have public static URLs.

For Java development, install Java 21 and Maven 3.9+, export those three password variables, and run `mvn spring-boot:run`. MongoDB defaults to `mongodb://localhost:27017/answer_assessment`; override using `MONGODB_URI`. Private originals default to `./data/scripts`; override using `SCRIPT_STORAGE`.

## End-to-end manual assessment

1. Sign in as teacher and create an exam.
2. Open Rubrics, choose the exam, add criteria and save.
3. Upload PDF, PNG or JPEG answer scripts, up to 20 MB each (10 per request).
4. Open a script. The original is fetched from a protected endpoint; enter the verified answer transcription, score and remarks.
5. Save a draft or submit for moderation.
6. Sign in as moderator, inspect the submission, then approve from Moderation.
7. Export approved marks as CSV from Reports.

Exams, exam-specific rubrics, uploaded script metadata, transcriptions, scores and historical reviews persist in MongoDB. Originals persist in a private Docker volume. Rubrics are snapshotted at upload so later rubric changes do not alter existing score limits. Submitted and approved answers cannot be overwritten. Each save stores a historical transcription, score, remarks, identity and timestamp. Optimistic locking rejects stale workspace writes (reload and retry). Approval requires a different identity from the reviewer. CSRF protection and role checks run on the server.

## Current scope and limits

This is a **single-school manual assessment MVP**, not a finished AI grading product. New scripts support multiple question answers, moderation returns, and appeals. Automatic region segmentation, automatic score suggestions, strong handwriting model integration, and certificate-signed PDFs remain future work. The workbench says manual transcription rather than inventing OCR output or evidence. Previous/next controls navigate the saved question answers.

Document uploads check size and file signatures. Full parser validation, malware scanning, file quotas, retention jobs and orphan-file cleanup after crashes are still needed for an internet-facing deployment. Uploaded PDF preview uses the browser's native PDF viewer; image preview uses the original image. No third-party OCR service receives documents.

The school workspace is a single MongoDB aggregate for atomic revisions and approval without a replica-set transaction. This is suitable for small local academic demonstrations; split into collections and introduce transactional design before scaling near MongoDB's 16 MB document limit. Multi-school isolation, user administration, production HTTPS/secure cookies, authenticated database setup, backups, login rate limits and stronger audit controls are deployment work still required. Session state is server-side and sign-in sessions expire after 30 minutes of inactivity.

## Interface

Routes: `/dashboard`, `/exams`, `/rubrics`, `/scripts`, `/upload`, `/review`, `/moderation`, `/reports`, `/profile`, `/login`. The reference-inspired lavender design and mobile bottom navigation are retained. The supplied image is a composite of several views; the implementation provides separate usable routes. Pixel-identical visual verification has not been completed.

## Validation

```sh
node --check src/main/resources/static/js/app.js
mvn verify
```

Tests cover page routing, anonymous access, CSRF, role enforcement, score bounds, blank-answer submission, immutable approved scores, independent approval and review history. GitHub Actions runs Maven verification. JavaScript syntax passes locally; full Maven and browser tests remain dependent on the execution environment's networking and browser support.

## Next workflow update

New uploads create one answer record per question in the selected exam. Each question gets a copy of the exam's shared rubric, its own transcription, score, review identity and original-page reference. Save a question before moving on (navigation also saves writable questions). Submission is allowed only after every question has a nonblank reviewed transcription. Script totals sum the question scores. Existing scripts remain compatible as one-answer scripts; new question counts apply only to new uploads.

Moderators can return pending submissions with a required reason. Teachers can open an appeal on an approved script from Reports. The last approved snapshot remains available in CSV exports during re-marking. A new independent approval resolves the appeal and appends a replacement approval snapshot, preserving earlier approvals and review history.

The original-document page reference is manual and does not crop or automatically segment PDF regions. Automatic question segmentation remains future work. Review history includes question-score snapshots. The export always uses the latest approved revision, never an unapproved appeal draft.

## Document regions, local OCR and PDF reports

Open an editable answer and choose **Select Answer Region**. Load its original page and drag a rectangle, or enter normalized x/y/width/height values from 0 to 1. Saving validates the actual page and region bounds. Changing a region invalidates the question's reviewed flag and cancels its old OCR job. Regions are manual selections, not automatic segmentation.

**Run Local OCR** saves the current question and queues a persistent local job. A single scheduled worker claims it with optimistic locking and retries abandoned running jobs after two minutes. Tesseract runs on the selected region with Bengali and English language data and a 45 second timeout. Docker includes the engine; Java-only development needs `tesseract` and `ben`/`eng` language data installed locally. A failed attempt records FAILED and leaves manual text and marks untouched. Use **Refresh Status**, then **Copy Draft for Correction**; compare it with the original and save manually. Tesseract is a baseline, not validated Bangla handwriting recognition, and no AI score is generated. Jobs and regions are stored in the school aggregate, so large-scale job queues remain future architecture work.

Reports now offer a PDF and a **Signed Bundle** ZIP containing `approved-marks.pdf`, `manifest.json`, `manifest.sig`, and `public-key.pem`. The manifest binds the PDF checksum, workspace version, approved snapshots and original-document checksums. Its Ed25519 signature is detached; the PDF is **not** a PAdES/certificate-signed PDF. A signing key is generated on the first signed export and retained in the private scripts volume. Back up that volume securely. One application instance must own the signing key; multi-instance key provisioning and certificate trust management are future deployment work.

Verify with Python and `cryptography` installed:

```sh
python -m pip install cryptography
python scripts/verify-report.py approved-marks-signed.zip
python scripts/verify-report.py approved-marks-signed.zip --trusted-key school-public-key.pem
```

The first command checks bundle integrity using its included key. Establish the school's key independently and use `--trusted-key` to authenticate subsequent exports; an included key alone cannot prove who signed. The report uses ASCII script/exam identifiers and numeric marks; Bangla typography in exported PDFs is not implemented. PDF rendering and image decoding are capped at 16 megapixels per page; full parser isolation and malware scanning remain deployment work.

The local browser address is `http://localhost:8085`. Docker publishes host port 8085 to container port 8080. Java-only runs also default to 8085; set `PORT` to override that default.


## Question-specific rubrics and criterion evidence

In Rubrics, choose an exam, then either its default rubric or a specific question. Question rubrics override the default for that question. New uploads require a rubric for every question, provided through overrides or the default. Each question saves its own rubric snapshot and mark limit.

For new scripts created after a rubric is saved with this release, markers score each criterion and paste an exact supporting excerpt from the verified transcription for positive marks. The server checks criterion bounds, validates that excerpts occur in the transcription, and rejects totals that differ from the sum of criterion marks. Excerpts establish traceability, not semantic correctness; deciding whether the answer satisfies a criterion remains a human task. No automatic or AI mark suggestion is claimed.

Existing scripts retain their original manual-total mode and rubric limits. Saved criterion marks and evidence are included in answer revision history and approved snapshots, so appeals and signed manifests preserve them.
