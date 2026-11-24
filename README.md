# Bangla Handwritten Answer Assessment Assistant

Spring Boot + Thymeleaf frontend based on the supplied lavender academic UI reference. Includes a MongoDB connection configuration and Docker Compose setup. No React build or Node runtime is required to run the application.

## Run with Docker

Install Docker Desktop, open a terminal in this repository, and run:

```sh
docker compose up --build
```

Open http://localhost:8080/dashboard or http://localhost:8080/login.

## Run with Java

Requires Java 21 and Maven 3.9+.

```sh
mvn spring-boot:run
```

MongoDB is not needed to explore the frontend; its configured default URI is `mongodb://localhost:27017/answer_assessment`. Override it using `MONGODB_URI`. Mongo repositories and backend persistence are not implemented yet. MongoDB may log connection errors when started without a database.

## Screens

| Route | Experience |
| --- | --- |
| `/login` | Reference-style sign in, password visibility and demo entry |
| `/dashboard` | Summary cards, recent scripts and marking workflow |
| `/exams` | Exam list and validated create-exam dialog |
| `/rubrics` | Editable Bangla criteria with dynamic mark totals |
| `/scripts` | Search, exam filtering and status filtering |
| `/upload` | File selection, validation and local script metadata |
| `/review` | Answer preview, transcription editor, evidence, score, remarks and history |
| `/moderation` | Demo approval queue |
| `/reports` | Approved results and CSV export |
| `/profile` | Demo profile and reset |

The design uses the reference's purple actions, lavender borders, pastel summary cards, rounded panels, Bangla text, split review workbench and mobile bottom navigation. The reference is a composite of several screens; each panel is implemented as its own usable route. The notebook is an illustrative typeset sample, not a scan or real OCR result. Login decoration uses CSS books rather than the photograph in the reference, so this is not a pixel-identical reproduction.

## Working frontend interactions

Create exams, edit the sample rubric, filter scripts, edit transcription and scores, save drafts, submit a review, approve a sample result and export approved marks. Changes persist in browser localStorage. Use Profile → Reset Demo Data to start over. The sample rubric is shared across the demo workspace; exam/question-specific rubric versioning is not implemented. There is one sample answer and a ten-question indicator; previous/next controls explain that additional segmented answers require backend integration.

Uploads validate extensions and sizes and save metadata only. They do not upload file bytes, retain documents, perform OCR or manufacture recognition results. New script records show that document integration is pending.

## Backend additions still needed

1. Spring Security session login, CSRF protection, teacher/marker/moderator permissions and tenant isolation. Current sign in only validates the form and opens the demo.
2. MongoDB domain models, repositories, service layer, exam-specific versioned rubrics and append-only mark revisions.
3. Private document storage, server-side upload validation, PDFBox processing and PDF.js document preview/segmentation.
4. A licensed Bangla handwriting OCR worker, persistent retryable jobs and manual transcription fallback.
5. Evidence-based score suggestions, independent moderator authorization and approval bound to a reviewed revision.
6. Appeals, audit history and signed PDF reports. Current evidence and AI score cards are illustrative sample data; CSV is not digitally signed.

Do not use this frontend demo for real assessment decisions or private student documents before implementing those services. Docker's MongoDB service is private to the Compose network and uses a persistent volume; configure production credentials and backups before deployment. Approval transactions will need a MongoDB replica set.

## Structure

- `src/main/java/com/assessment/controller/PageController.java`: Spring MVC routes
- `src/main/resources/templates/app.html`: Thymeleaf application shell
- `src/main/resources/static/css/app.css`: responsive reference-inspired styling
- `src/main/resources/static/js/app.js`: frontend views and local demo state
- `src/test/java/com/assessment/PageRoutesTest.java`: route tests without MongoDB
- `.github/workflows/verify.yml`: Java build, route tests and JavaScript syntax check

## Validation

```sh
node --check src/main/resources/static/js/app.js
mvn verify
```

JavaScript syntax was checked during implementation. Maven dependency downloads and browser rendering could not complete in the authoring environment because of network/process restrictions; the full Java build and visual comparison remain unverified. GitHub Actions runs the build on push.
