# Product 33 · Bangla Handwritten Answer Assessment Assistant

Academic assessment prototype for Southeast University’s Cryptography and Network Security course. Teachers prepare exams and publish signed rubrics. Assigned markers review the handwriting and submit criterion marks. Assigned moderators independently approve, return or resolve an appeal.

## Run locally

Requires Docker Desktop or Docker Compose. On Windows, run these commands from the project directory in PowerShell.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Before starting Compose, set three distinct passwords with at least 12 characters in `.env` and set `ASSESSMENT_WORKER_TOKEN` to a random string of at least 32 characters. With OpenSSL, use `openssl rand -hex 32` for the worker token. Open [http://localhost:8085/login](http://localhost:8085/login).

| Account | Role |
| --- | --- |
| `teacher@school.local` | Create exams, publish rubrics, upload and assign scripts, view approved reports |
| `marker@school.local` | Segment assigned answers, request OCR, verify text, score and submit |
| `moderator@school.local` | Review assigned submissions, return or approve them, resolve appeals and export approved marks |

Compose starts a MongoDB replica set and an isolated PDFBox/Tesseract worker. The application is bound to localhost. MongoDB and the worker have no published host ports. MongoDB uses a named volume. Originals, encryption keys and the signing registry use the private scripts volume. The browser views rendered pages and can download the original after an authorization check.

For Java development, install Java 21, Maven, MongoDB and Tesseract with `ben` and `eng` data. Configure the three role passwords and run `mvn spring-boot:run`. Spring Boot listens on port 8080 by default. Set `MONGODB_URI` for your database. Enable `ASSESSMENT_PARTITIONED=true` with a replica set to use the Compose storage layout, or run against a separate standalone development database using the aggregate layout. An existing partitioned database requires replica-set mode.

Upgrading an older Compose installation with a root-owned scripts volume requires adjusting its ownership once. Stop the application and back up the volumes before running `docker compose run --rm --no-deps --user root --entrypoint chown app -R 10001:10001 /data/scripts`. Replica-set mode migrates the existing structured workspace on its first successful write. Previously uploaded plaintext originals remain readable from their legacy location. Newly accepted scripts are always encrypted.

## Update an existing Windows installation

Run this in the original repository folder so Compose uses the same MongoDB and scripts volumes. If you previously started Compose with a custom project name, use that same `COMPOSE_PROJECT_NAME` for the update.

```powershell
git pull --ff-only origin main
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-local.ps1
```

The updater keeps existing passwords, generates a missing worker token, builds both images, and adjusts the old scripts volume's file ownership. Open [http://localhost:8085/login](http://localhost:8085/login) and confirm **Build 2026.11.26-r3.1**. The Scripts table shows the actual exam, rubric revision, and available actions. Use **Change Exam / Rubric** to correct an existing upload while it is still **To Review**. Teachers configure exams and rubrics, and the assigned marker runs OCR and assessment.

## Complete an assessment

1. Sign in as the teacher. Create an exam with a unique reference, a description and an exact maximum for each question. Use **Edit Exam** to update the saved record.
2. Publish each question’s rubric. Criterion limits must add up to the question maximum. Optional evidence hints accept exact phrases, with `|` between alternatives. Published versions stay available under **Published Versions**.
3. Upload a PDF, PNG or JPEG and choose the exact exam and assessment rubric. Choose **Whole exam** for scripts covering all questions, or choose one published rubric for a single assessment. Each upload retains the chosen rubric revision. Teachers can correct the exam or rubric from the Scripts list before marker review begins.
4. Sign in as the assigned marker and choose **Auto OCR & Assess**. The job extracts each page, builds answer drafts from recognizable question headings, and suggests marks for matched rubric phrases. Open **Script OCR** to inspect pages or recover text when a heading could not be read. Use manual regions and **Run Region OCR** for precise crops.
5. Review the OCR draft and its source pages. **Use Matched Suggestions** fills supported criteria in the marker draft. Review every criterion and add a reason if you change a suggested score. Save Question persists partial criterion drafts too. To mark directly from the image, select **Mark manually from the original**, choose the evidence region and record why. An unreadable answer starts with an empty score.
6. Submit the script. The assigned moderator can inspect the evidence, approve the submission, or return it with a reason.
7. Open an appeal from **Reports**. The app retains the previous signed approval while reassessment is in progress. A moderator may uphold the previous marks with a reason or independently approve the revised marks.
8. Export CSV, PDF or a **Signed Bundle**. Under **Verify**, upload a signed rubric or mark-sheet ZIP to check its digest, issuer key and revision status.

## Main API

All endpoints require an authenticated institutional session. POST requests require the CSRF token from `GET /api/csrf`. Candidate and script data are scoped to the tenant and assigned role.

| Request | Fields and result |
| --- | --- |
| `POST /api/v1/p33/script-assessments` | Send `script_id`, `rubric_version` (the script’s `rubricSetVersion`), and the `Idempotency-Key` header. Returns 202 and `job_id`. |
| `GET /api/v1/jobs/{id}` | Returns state, progress, attempt count, input hash, model and output references. |
| `POST /api/v1/p33/mark-reviews` | Send `answer_id`, `score`, `rationale`, `expected_version`, and per-criterion `awards` with `marks`, `evidence` and an optional zero-based `region`. Optional fields include `transcription`, `page`, `manual_review` and `submit`. Returns `revision_id`. |
| `POST /api/v1/reviews` | Send the script or submitted revision ID as `resource_id`, `expected_version`, `decision` (`APPROVE` or `RECHECK`), and a `reason` when returning. Returns a `decision_id`. |
| `GET /api/rubrics/{id}/signed` | Downloads the immutable rubric ZIP by version. |
| `POST /api/verify` | Upload a signed ZIP in multipart field `file`. Returns `VALID`, `ALTERED`, `UNKNOWN_KEY`, `REVOKED_KEY`, `SUPERSEDED` or `UNAVAILABLE`. |

Invalid versioned requests return 422. Stale writes and conflicting idempotency payloads return 409. An unknown or inaccessible resource returns a concealed 404.

## Deployment and verification

- For institutional accounts, set `ASSESSMENT_ACCOUNTS_JSON` to an array with `email`, `tenant`, `role`, `active` and a BCrypt `passwordHash` per member. Use distinct, lower-case email addresses. Single-quote the JSON in Compose’s .env file so BCrypt dollar signs stay literal. Account changes take effect after an application restart, which ends existing sessions.
- For HTTPS, set `ASSESSMENT_HTTPS_HOST`, `ASSESSMENT_SECURE_COOKIES=true` and `ASSESSMENT_FORWARD_HEADERS=framework`, then run `docker compose --profile https up --build`. Caddy needs a certificate trusted by the client. For a public institutional deployment use your verified domain and an authenticated TLS-enabled MongoDB replica set through `MONGODB_URI`.
- To require TOTP for active teachers and moderators, set `ASSESSMENT_PILOT=true` and provide `ASSESSMENT_MFA_SECRETS_JSON` as an email-to-Base64 secret map. Each secret needs at least 20 random bytes. Configure the equivalent Base32 key in the user’s authenticator with a six-digit SHA-1 code and a 30-second period. The prototype blocks OTP replay within the running application.
- The application signs published rubrics and approvals using Ed25519. The report ZIP contains the PDF, signed manifest and public key. An independently trusted key is required to authenticate the issuer. The institutional Verify page checks revision and key status. The PDF uses a detached ZIP signature. For offline verification, install Python’s `cryptography` package and run `python scripts/verify-report.py approved-marks-signed.zip --trusted-key school-public-key.pem`.
- Signing keys and encryption keys are separate. For an offline signing-key rotation or revocation see `python scripts/signing-admin.py --help`. Protect the private volume or supply a 32-byte Base64 `ASSESSMENT_ENCRYPTION_KEY` through your deployment secret manager. Include encryption keys in the protected recovery procedure. To rotate AES keys, add a new version to the single-quoted `ASSESSMENT_ENCRYPTION_KEYS_JSON` map (for example version `"2"` with a distinct 32-byte Base64 key) and set `ASSESSMENT_ENCRYPTION_ACTIVE_VERSION=2`. Keep old keys available for existing encrypted records and originals.

### Backup and recovery

Pause the application for a consistent prototype backup. Capture a gzip `mongodump` of the database and a tar archive of the whole scripts volume, including hidden key and registry files. Age-encrypt the bundle for the recovery operator before moving it to backup storage. Keep external encryption and MFA secrets in the institution’s secret-management backup. Restore the Mongo dump and scripts volume into a fresh isolated stack with the matching key. Verify role access, original SHA-256 checks and a previously signed bundle. Record restore duration and possible data loss against the SRS’s proposed RTO and RPO. Retain or remove assessment records according to the institution’s approved retention schedule.

## Build and acceptance checks

```sh
node --check src/main/resources/static/js/app.js
node scripts/frontend-smoke.cjs
mvn verify
```

For the desktop and mobile browser workflow, install Playwright and Chromium, start a disposable local stack, export the three passwords and run `node scripts/browser-acceptance.cjs`. Use `P33_BROWSER_URL` to override the local address.

Set `OCR_NATIVE_TEST=true` to exercise native OCR. Set `P33_TEST_MONGO_URI` to a **disposable replica-set database** to run the full signed workflow acceptance test. It checks isolated tenant and assignment access, rubric validation, tamper detection, idempotency, manual review without a default zero, score limits, independent approval, stale transitions and the preservation of a 6-mark approval when an appeal changes it to 7.

The default OCR is a local Tesseract Bengali/English baseline. Scoring suggestions use exact rubric hints and source spans. Handwritten Bangla accuracy and reviewer time require a consented, writer-disjoint evaluation. Run `python scripts/evaluate.py test-cases.jsonl` on approved data. Each case has `writer`, `split`, `reference_transcription`, `ocr_transcription` and `abstained`. Optional fields include `reference_fields`, `ocr_fields`, `reference_evidence`, `linked_evidence`, `review_corrected`, `review_seconds` and `manual_seconds`.

The acceptance run passed 40 Maven tests and completed the Chromium workflow on desktop and mobile with the standalone document worker. A synthetic encrypted backup restored the original checksum, approved revision, role permissions and trust registry in approximately five seconds.

A loopback load check for `GET /api/workspace` used 10 distinct concurrent accounts, 10,000 structured records including 1,000 scripts, and 60 timed requests after warm-up. It measured 525 ms median and 782 ms p95. The Linux test environment had 9 logical CPUs and 9.7 GiB memory. The Java process was configured to use 2 processors and ran with MongoDB 7.0.28.

The academic deployment uses a single application instance. Institution-sized recovery exercises and held-out handwriting evaluation remain pilot acceptance work.
