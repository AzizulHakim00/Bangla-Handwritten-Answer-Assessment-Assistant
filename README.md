# Bangla Handwritten Answer Assessment Assistant

Spring Boot 3.5 / Java 21, Thymeleaf, MongoDB and Docker. The lavender academic interface now uses authenticated server APIs rather than browser demo data.

## Start locally

1. Install Docker Desktop.
2. Copy `.env.example` to `.env`.
3. Set three **different** passwords, each at least 12 characters.
4. Run `docker compose up --build`.
5. Open http://localhost:8080/login.

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

This is a **single-school manual assessment MVP**, not a finished AI grading product. There is one assessed answer per script. Multi-question segmentation, individual criterion scoring, OCR workers, automatic score suggestions, moderation rejection/reopening, appeals, and signed PDF exports remain future work. The workbench says manual transcription rather than inventing OCR output or evidence. Previous/next controls explain that more answers require segmentation.

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
