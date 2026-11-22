#!/usr/bin/env python3
"""Export current and historical benchmark results to an executive-summary PDF."""

import argparse
import json
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path

from PIL import Image as PillowImage
from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    Image,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent
NAVY = colors.HexColor("#12243A")
TEAL = colors.HexColor("#0C9488")
INK = colors.HexColor("#273746")
MUTED = colors.HexColor("#5E6B75")
PALE = colors.HexColor("#EEF4F6")
LINE = colors.HexColor("#D7E0E5")
WARNING = colors.HexColor("#FFF4E5")


def load_json(path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--current",
        type=Path,
        default=ROOT / "reproducible_benchmark_results.json",
    )
    parser.add_argument(
        "--historical",
        type=Path,
        default=ROOT / "benchmark_results.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "OpenTron_Benchmark_Executive_Summary.pdf",
    )
    return parser.parse_args()


def paragraph_styles():
    styles = getSampleStyleSheet()
    styles.add(
        ParagraphStyle(
            name="TitleOpenTron",
            parent=styles["Title"],
            fontName="Helvetica-Bold",
            fontSize=23,
            leading=27,
            textColor=NAVY,
            alignment=TA_LEFT,
            spaceAfter=5,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SectionOpenTron",
            parent=styles["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=14,
            leading=17,
            textColor=NAVY,
            spaceBefore=8,
            spaceAfter=6,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SubsectionOpenTron",
            parent=styles["Heading3"],
            fontName="Helvetica-Bold",
            fontSize=10,
            leading=13,
            textColor=TEAL,
            spaceBefore=5,
            spaceAfter=3,
        )
    )
    styles.add(
        ParagraphStyle(
            name="BodyOpenTron",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=8.5,
            leading=12,
            textColor=INK,
            spaceAfter=4,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SmallOpenTron",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=7.1,
            leading=9.2,
            textColor=MUTED,
            spaceAfter=2,
        )
    )
    styles.add(
        ParagraphStyle(
            name="TableHeaderOpenTron",
            parent=styles["BodyText"],
            fontName="Helvetica-Bold",
            fontSize=7.2,
            leading=8.5,
            textColor=colors.white,
            alignment=TA_LEFT,
        )
    )
    styles.add(
        ParagraphStyle(
            name="CalloutOpenTron",
            parent=styles["BodyText"],
            fontName="Helvetica-Bold",
            fontSize=9.2,
            leading=13,
            textColor=NAVY,
            spaceAfter=0,
        )
    )
    return styles


def make_table(rows, widths, header=True, background=None, font_size=7.5):
    table = Table(rows, colWidths=widths, repeatRows=1 if header else 0, hAlign="LEFT")
    commands = [
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("GRID", (0, 0), (-1, -1), 0.35, LINE),
        ("FONTNAME", (0, 0), (-1, -1), "Helvetica"),
        ("FONTSIZE", (0, 0), (-1, -1), font_size),
        ("TEXTCOLOR", (0, 0), (-1, -1), INK),
    ]
    if header:
        commands.extend(
            [
                ("BACKGROUND", (0, 0), (-1, 0), NAVY),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ]
        )
    if background:
        commands.append(("BACKGROUND", (0, 1 if header else 0), (-1, -1), background))
    else:
        commands.append(
            (
                "ROWBACKGROUNDS",
                (0, 1 if header else 0),
                (-1, -1),
                [colors.white, PALE],
            )
        )
    table.setStyle(TableStyle(commands))
    return table


def header_row(labels, styles):
    return [Paragraph(label, styles["TableHeaderOpenTron"]) for label in labels]


def add_callout(story, text, styles, background=PALE):
    box = Table([[Paragraph(text, styles["CalloutOpenTron"])]], colWidths=[178 * mm])
    box.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), background),
                ("BOX", (0, 0), (-1, -1), 0.7, TEAL),
                ("LEFTPADDING", (0, 0), (-1, -1), 9),
                ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                ("TOPPADDING", (0, 0), (-1, -1), 8),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
            ]
        )
    )
    story.extend([box, Spacer(1, 7)])


def page_chrome(canvas, document):
    canvas.saveState()
    width, height = A4
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.6)
    canvas.line(16 * mm, 13 * mm, width - 16 * mm, 13 * mm)
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(MUTED)
    canvas.drawString(16 * mm, 8.5 * mm, "OPENTRON  |  BENCHMARK EXECUTIVE SUMMARY")
    canvas.drawRightString(width - 16 * mm, 8.5 * mm, f"{document.page}")
    canvas.restoreState()


def current_section(story, current, styles):
    host = current["host"]
    workload = current["workload"]
    run_date = datetime.fromisoformat(current["timestamp_utc"]).astimezone(timezone.utc)
    story.append(Paragraph("Current controlled comparison", styles["SectionOpenTron"]))
    story.append(
        Paragraph(
            f"Run {run_date:%d %b %Y, %H:%M UTC} &nbsp;|&nbsp; "
            f"{host.get('cpu_model', host.get('processor', 'Unknown CPU'))} &nbsp;|&nbsp; "
            f"{host.get('physical_cores', 'unknown')} physical / "
            f"{host.get('logical_cpus', 'unknown')} logical cores &nbsp;|&nbsp; "
            f"{host.get('physical_memory_gib', 'unknown')} GiB RAM &nbsp;|&nbsp; "
            f"Docker {host['docker']['version']}",
            styles["BodyOpenTron"],
        )
    )

    java = {
        row["concurrency_batches"]: row
        for row in current["results"]
        if row["stack"] == "opentron-java"
    }
    python = {
        row["concurrency_batches"]: row
        for row in current["results"]
        if row["stack"] == "python-celery"
    }
    table_rows = [
        header_row(
            [
                "Concurrent<br/>batches",
                "Tasks in<br/>flight",
                "Java<br/>tasks/s",
                "Java p99<br/>ms",
                "Python<br/>tasks/s",
                "Python p99<br/>ms",
            ],
            styles,
        )
    ]
    for level in sorted(java):
        jrow, prow = java[level], python[level]
        table_rows.append(
            [
                str(level),
                str(level * workload["batch_size"]),
                f"{jrow['throughput_tasks_per_second']:,.2f}",
                f"{jrow['latency_ms']['p99']:,.2f}",
                f"{prow['throughput_tasks_per_second']:,.2f}",
                f"{prow['latency_ms']['p99']:,.2f}",
            ]
        )
    story.append(
        make_table(
            table_rows,
            [52, 58, 76, 67, 83, 82],
        )
    )
    story.append(Spacer(1, 6))
    story.append(
        Paragraph(
            f"Each stack completed all {workload['requests_per_level']} requests at every "
            "concurrency level. Throughput counts successfully completed simulated tasks.",
            styles["SmallOpenTron"],
        )
    )

    memory = current["memory_stress"]
    memory_rows = [
        header_row(
            ["Stack", "Requests", "Idle MiB", "Peak MiB", "Peak increase MiB"],
            styles,
        )
    ]
    for stack in ("opentron-java", "python-celery"):
        result = memory[stack]
        stats = result["memory"]
        memory_rows.append(
            [
                stack,
                f"{result['successful_requests']}/{result['requests']}",
                f"{stats['idle_mib']:.1f}",
                f"{stats['peak_mib']:.1f}",
                f"{stats['peak_delta_mib']:.1f}",
            ]
        )
    story.append(Paragraph("Memory", styles["SubsectionOpenTron"]))
    story.append(make_table(memory_rows, [120, 63, 67, 67, 100]))
    story.append(Spacer(1, 5))

    story.append(Paragraph("Test conditions", styles["SubsectionOpenTron"]))
    story.append(
        Paragraph(
            f"Each HTTP batch starts {workload['batch_size']} tasks with a "
            f"{workload['delay_ms']} ms simulated I/O wait. Each stack was capped at "
            "2 vCPU and 2 GiB; the Python cap is split among FastAPI, three Celery workers "
            "(30 prefork slots total), and Redis. p99 is end-to-end batch latency using "
            "nearest-rank percentile. The memory test runs "
            f"{workload['memory_concurrent_requests']} simultaneous requests of "
            f"{workload['memory_tasks_per_request']} tasks at "
            f"{workload['memory_delay_ms']} ms delay.",
            styles["BodyOpenTron"],
        )
    )
    add_callout(
        story,
        "Scope: this is a controlled I/O-wait scheduling benchmark, not an LLM, database, "
        "or full multi-agent reasoning workload. It does not establish a general claim "
        "about all Python or enterprise frameworks.",
        styles,
        WARNING,
    )
    story.append(
        Paragraph(
            "Python implementation note: each API process serializes Celery group-result "
            "collection through its shared Redis result consumer; task execution remains "
            "concurrent. This is part of the measured baseline and contributes to its "
            "high-concurrency tail latency.",
            styles["SmallOpenTron"],
        )
    )


def historical_section(story, historical, styles):
    recorded = datetime.fromtimestamp(historical["timestamp"], timezone.utc)
    story.append(Paragraph("Previous benchmark (historical)", styles["SectionOpenTron"]))
    story.append(
        Paragraph(
            f"Raw data timestamp: {recorded:%d %b %Y, %H:%M UTC}. "
            "These results are retained for context, not pooled with the current run.",
            styles["BodyOpenTron"],
        )
    )

    rows_by_level = {}
    for result in historical.get("results", []):
        rows_by_level.setdefault(result["concurrency"], {})[result["platform"]] = result

    table_rows = [
        header_row(
            [
                "Concurrency",
                "Java successful",
                "Java tasks/s",
                "Java p99 ms",
                "Python result",
            ],
            styles,
        )
    ]
    for level in sorted(rows_by_level):
        entries = rows_by_level[level]
        java = entries.get("opentron-java")
        python = entries.get("python-celery")
        table_rows.append(
            [
                str(level),
                (
                    f"{java['successful_tasks']}/{java['total_tasks']}"
                    if java
                    else "Not recorded"
                ),
                (
                    f"{java['throughput_tasks_per_second']:,.2f}"
                    if java
                    else "Not recorded"
                ),
                f"{java['p99_latency_ms']:.2f}" if java else "Not recorded",
                (
                    f"{python['successful_tasks']}/{python['total_tasks']} "
                    f"({python.get('note', 'failure')})"
                    if python
                    else "Not recorded"
                ),
            ]
        )
    story.append(make_table(table_rows, [66, 83, 78, 70, 180]))
    story.append(Spacer(1, 7))
    add_callout(
        story,
        "The legacy JSON records five successful Java measurements and one Python "
        "connection-refused result at concurrency 10 (0/100 tasks). Its zero-valued "
        "Python latency fields are placeholders, not measured 0 ms latency.",
        styles,
        WARNING,
    )
    story.append(
        Paragraph(
            "The historical JSON contains no RAM measurements or hardware/resource-limit "
            "metadata. The accompanying legacy Markdown report states a different generation "
            "date (29 Jul 2026) than the raw JSON timestamp (29 Jul 2024), and describes "
            "additional Python concurrency levels not present in the JSON. This section "
            "therefore uses only the raw JSON records; the two runs are not directly "
            "comparable.",
            styles["BodyOpenTron"],
        )
    )
    story.append(Paragraph("Sources", styles["SubsectionOpenTron"]))
    story.append(
        Paragraph(
            "Current: benchmarks/reproducible_benchmark_results.json and "
            "benchmarks/REPRODUCIBLE_BENCHMARK_REPORT.md.<br/>"
            "Historical: benchmarks/benchmark_results.json; legacy narrative: "
            "benchmarks/BENCHMARK_REPORT.md.",
            styles["SmallOpenTron"],
        )
    )


def governance_section(story, styles):
    story.append(
        Paragraph("Deterministic governance building blocks", styles["SectionOpenTron"])
    )
    story.append(
        Paragraph(
            "OpenTron exposes several application-level control points relevant to "
            "regulated deployments. They support explicit configuration and state "
            "management; they are not, by themselves, a compliance certification or "
            "a formal proof of policy correctness.",
            styles["BodyOpenTron"],
        )
    )
    governance_rows = [
        header_row(
            ["Control surface", "Implementation-backed capability", "Boundary / operational responsibility"],
            styles,
        )
    ]
    entries = [
        (
            "Typed contracts",
            "Java records define gateway principals and API request/response shapes. "
            "GatewayPrincipal validates bounded identity/scope fields and freezes its scope set.",
            "Typed boundaries make inputs explicit; they do not prove every business policy "
            "or data flow is valid.",
        ),
        (
            "Persistent state",
            "Default/production configuration supports PostgreSQL, Flyway schema migrations, "
            "JPA repositories, and transactional storage services. Desktop mode uses a file-backed H2 database.",
            "Backups, retention, access control, and recovery objectives remain deployment "
            "responsibilities. The performance benchmark did not exercise the database.",
        ),
        (
            "Network and model routing",
            "A central network policy gates cloud-model and web-search paths; when outbound "
            "internet is disabled, routing selects local Ollama. The policy is stored under ~/.opentron.",
            "The default allows internet, cloud routes remain available when enabled, and "
            "policy-file I/O failures are currently best-effort rather than fail-closed.",
        ),
        (
            "Hardware choice",
            "The standard JVM plus PostgreSQL or embedded-H2 deployment does not require "
            "confidential-computing hardware.",
            "Optional AWS Nitro and GCP CVM profiles are hardware-specific. Local model "
            "inference still depends on the configured model and host resources.",
        ),
    ]
    for surface, capability, boundary in entries:
        governance_rows.append(
            [
                Paragraph(surface, styles["SmallOpenTron"]),
                Paragraph(capability, styles["SmallOpenTron"]),
                Paragraph(boundary, styles["SmallOpenTron"]),
            ]
        )
    story.append(make_table(governance_rows, [74, 224, 198]))
    story.append(Spacer(1, 8))
    add_callout(
        story,
        "Positioning: these controls can support local and on-prem governance without "
        "mandatory enclave hardware. The repository and this benchmark contain no "
        "head-to-head OPAQUE evaluation, security certification, or evidence of feature parity.",
        styles,
        WARNING,
    )
    story.append(
        Paragraph(
            "The benchmark measures synthetic I/O scheduling only; it does not test "
            "policy enforcement, isolation, database recovery, or regulatory controls.",
            styles["SmallOpenTron"],
        )
    )
    story.append(
        Paragraph(
            "Implementation sources: backend configuration, NetworkPolicyService, "
            "ConditionalRouter, StorageService, and GatewayPrincipal.",
            styles["SmallOpenTron"],
        )
    )


def build_pdf(current_path, historical_path, output_path):
    current = load_json(current_path)
    historical = load_json(historical_path)
    styles = paragraph_styles()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(output_path),
        pagesize=A4,
        rightMargin=16 * mm,
        leftMargin=16 * mm,
        topMargin=14 * mm,
        bottomMargin=18 * mm,
        title="OpenTron Benchmark Executive Summary",
        author="OpenTron",
        subject="Current and historical Java virtual-thread/Python benchmark results",
    )
    story = []
    logo_path = REPO_ROOT / "assets" / "OpenTron_Horizontal_Logo.png"
    if logo_path.exists():
        with PillowImage.open(logo_path) as source:
            logo_width, logo_height = source.size
            source.thumbnail((1000, 335), PillowImage.Resampling.LANCZOS)
            logo_buffer = BytesIO()
            source.convert("RGB").save(
                logo_buffer,
                format="JPEG",
                quality=88,
                optimize=True,
            )
        logo_buffer.seek(0)
        display_width = 66 * mm
        display_height = display_width * logo_height / logo_width
        story.append(
            Image(
                logo_buffer,
                width=display_width,
                height=display_height,
                kind="proportional",
            )
        )
        story.append(Spacer(1, 4))
    story.append(
        Paragraph("OpenTron Benchmark Executive Summary", styles["TitleOpenTron"])
    )
    story.append(
        Paragraph(
            "Java 21 virtual threads compared with a FastAPI/Celery/Redis baseline",
            styles["BodyOpenTron"],
        )
    )
    story.append(Spacer(1, 4))
    levels = sorted(
        row["concurrency_batches"]
        for row in current["results"]
        if row["stack"] == "opentron-java"
    )
    last_level = levels[-1]
    latest_java = next(
        row
        for row in current["results"]
        if row["stack"] == "opentron-java"
        and row["concurrency_batches"] == last_level
    )
    latest_python = next(
        row
        for row in current["results"]
        if row["stack"] == "python-celery"
        and row["concurrency_batches"] == last_level
    )
    add_callout(
        story,
        f"At {last_level} concurrent batch requests "
        f"({last_level * current['workload']['batch_size']} simulated tasks in flight), "
        f"Java measured {latest_java['throughput_tasks_per_second']:,.2f} tasks/s "
        f"at {latest_java['latency_ms']['p99']:,.2f} ms p99; Python measured "
        f"{latest_python['throughput_tasks_per_second']:,.2f} tasks/s at "
        f"{latest_python['latency_ms']['p99']:,.2f} ms p99.",
        styles,
    )
    current_section(story, current, styles)
    story.append(PageBreak())
    governance_section(story, styles)
    story.append(PageBreak())
    historical_section(story, historical, styles)
    doc.build(story, onFirstPage=page_chrome, onLaterPages=page_chrome)

    reader = PdfReader(str(output_path))
    if len(reader.pages) < 3:
        raise RuntimeError("The executive summary PDF is unexpectedly short.")
    text = " ".join(
        " ".join((page.extract_text() or "").split()) for page in reader.pages
    )
    required = [
        "Current controlled comparison",
        "Deterministic governance building blocks",
        "Previous benchmark (historical)",
        f"{latest_java['throughput_tasks_per_second']:,.2f}",
        f"{latest_python['throughput_tasks_per_second']:,.2f}",
        "RAM measurements",
        "no head-to-head OPAQUE evaluation",
    ]
    missing = [item for item in required if item not in text]
    if missing:
        raise RuntimeError(f"PDF validation could not find expected text: {missing}")
    if not (
        text.index("Current controlled comparison")
        < text.index("Deterministic governance building blocks")
        < text.index("Previous benchmark (historical)")
    ):
        raise RuntimeError("PDF sections are not in the requested order.")
    return len(reader.pages)


def main():
    args = parse_args()
    pages = build_pdf(args.current, args.historical, args.output)
    print(f"Generated and validated {pages}-page PDF: {args.output.resolve()}")


if __name__ == "__main__":
    main()
