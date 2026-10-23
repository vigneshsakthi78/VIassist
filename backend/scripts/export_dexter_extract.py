"""Export the Dexter DMS date-range extract into Vicky Assist knowledge docs.

Reads MACK_DB_HOST, MACK_DB_USER, MACK_DB_PASSWORD, MACK_DB_NAME from the environment.
Does not query dmseventlog. Page Deleted and Circular Cancelled events are excluded.
"""

import html
import json
import os
import re
from datetime import datetime
from pathlib import Path

import pymysql

DOCS = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "docs"
PUB_FROM = "2026-07-25 00:00:00"
PUB_TO = "2026-08-28 00:00:00"
DEL_FROM = "2025-07-25 00:00:00"
DEL_TO = "2026-08-28 00:00:00"
BATCH = 40
CONTENT_LIMIT = 4000
DAY_EXCERPT = 700


def main():
    conn = pymysql.connect(
        host=os.environ["MACK_DB_HOST"],
        user=os.environ["MACK_DB_USER"],
        password=os.environ["MACK_DB_PASSWORD"],
        database=os.environ["MACK_DB_NAME"],
        connect_timeout=15,
        read_timeout=180,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    cur = conn.cursor()
    cur.execute("DROP TEMPORARY TABLE IF EXISTS DocumentsData")
    cur.execute("DROP TEMPORARY TABLE IF EXISTS AttachmentData")
    cur.execute(
        """
        CREATE TEMPORARY TABLE DocumentsData AS
        select dm.*, df.headercontent, df.footercontent
        from dmsdocumentmaster dm
        inner join dmsheadfootsave df
          on dm.docid=df.docid and dm.docversion=df.version
         and dm.ispublished='Y'
         and dm.docstatus not in ('dele','canc')
         and filetypeflag in ('h','f')
         and df.verstatus='A'
        where dm.publishdate BETWEEN %s AND %s
        """,
        (PUB_FROM, PUB_TO),
    )
    cur.execute(
        """
        CREATE TEMPORARY TABLE AttachmentData AS
        select dm.doctitle, dm.docversion, df.*
        from dmsdocumentmaster dm
        inner join dmsfilemanager df
          on dm.docid=df.docid
         and dm.ispublished='Y'
         and dm.docstatus not in ('dele','canc')
         and filetypeflag in ('F','H')
         and df.status='A'
        where dm.publishdate BETWEEN %s AND %s
        """,
        (PUB_FROM, PUB_TO),
    )
    cur.execute("SELECT COUNT(*) AS n FROM DocumentsData")
    doc_n = cur.fetchone()["n"]
    cur.execute("SELECT COUNT(*) AS n FROM AttachmentData")
    att_n = cur.fetchone()["n"]
    cur.execute(
        """
        SELECT COUNT(*) AS n
        FROM dmsdocvarmapping a
        INNER JOIN DocumentsData b ON a.docid=b.docid AND a.version=b.docversion
        WHERE b.publishdate BETWEEN %s AND %s
        """,
        (PUB_FROM, PUB_TO),
    )
    var_n = cur.fetchone()["n"]
    cur.execute(
        """
        SELECT id, templatecode, templatename, templatefield, status, type,
               companycode, styleref, datecr, templatedata
        FROM dmstemplates
        WHERE datecr >= %s
        """,
        (PUB_FROM,),
    )
    templates = cur.fetchall()
    cur.execute(
        """
        SELECT docid, doctitle, docrefno, docversion, userversion, docstatus, verstatus,
               doctype, filetypeflag, filename, parent, publishdate, deldate, deluser,
               reasonforrev, ispublished, workflowid, doccontent
        FROM dmsdocumentmaster
        WHERE docstatus IN ('DELE','canc')
          AND verstatus='A'
          AND publishdate BETWEEN %s AND %s
        ORDER BY publishdate, docid
        """,
        (DEL_FROM, DEL_TO),
    )
    deleted = cur.fetchall()
    attachments = fetch_attachments(cur, deleted)
    conn.close()

    write_summary(doc_n, att_n, var_n, templates, deleted)
    write_batches(deleted)
    write_documents(deleted)
    write_day_by_day(deleted, attachments)
    print(f"documents={doc_n} attachments={att_n} varmap={var_n} templates={len(templates)} deleted={len(deleted)}")


def cell(value):
    if value is None:
        return ""
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    return str(value).replace("\r\n", "\n").strip()


def write_summary(doc_n, att_n, var_n, templates, deleted):
    lines = [
        "Dexter DMS extract from mack_dms",
        f"Exported {datetime.now().strftime('%Y-%m-%d')} for Vicky Assist.",
        "",
        "Published window (DocumentsData, AttachmentData, variable mapping)",
        f"publishdate BETWEEN '{PUB_FROM}' AND '{PUB_TO}'.",
        "Filters: ispublished Y, docstatus not DELE/CANC, filetypeflag H or F,",
        "header/footer row joined on docid+version with dmsheadfootsave.verstatus A.",
        f"DocumentsData rows: {doc_n}",
        f"AttachmentData rows: {att_n}",
        f"Variable-mapping rows joined to DocumentsData: {var_n}",
        "",
        "Why the published set is empty on this database",
        "In that window, dmsdocumentmaster has two PUBD rows (DMSDOC319940 flowcher1, filetype H;",
        "DMSDOC319935 SMS, filetype P). The HTML page joins a header whose verstatus is V, not A,",
        "so the script's verstatus='A' filter drops it. The folder row is filetype P, also dropped.",
        "No active file-manager rows exist for the HTML page, so AttachmentData is empty.",
        "",
        "Templates with datecr on or after 2026-07-25",
        f"Rows: {len(templates)}",
    ]
    for row in templates:
        lines.append(
            " | ".join(
                [
                    cell(row["templatecode"]),
                    cell(row["templatename"]),
                    cell(row["templatefield"]),
                    cell(row["status"]),
                    cell(row["type"]),
                    cell(row["companycode"]),
                    cell(row["styleref"]),
                    cell(row["datecr"]),
                ]
            )
        )
        lines.append("Template HTML:")
        lines.append(cell(row["templatedata"]) or "(empty)")
        lines.append("")
    canc = sum(1 for r in deleted if str(r["docstatus"]).upper() == "CANC")
    dele = sum(1 for r in deleted if str(r["docstatus"]).upper() == "DELE")
    lines += [
        "Deleted and cancelled documents kept for Dexter",
        f"docstatus DELE or CANC, verstatus A, publishdate BETWEEN '{DEL_FROM}' AND '{DEL_TO}'.",
        f"Rows: {len(deleted)} (DELE {dele}, CANC {canc}).",
        "Document ids are batched in 46-dexter-deleted-cancelled-docids.txt.",
        "Metadata and page HTML are in 47-dexter-deleted-cancelled-documents.txt.",
        "Day-by-day records, with content text and attachment names, are in 48-dexter-day-by-day.txt.",
        "A docid search summarizes that content and lists attachments from the same extract.",
        "",
        "Excluded on purpose",
        "dmseventlog rows for pageid DMSDOC21657 action Page Deleted were not loaded.",
        "dmseventlog rows for action Page Deleted or Circular Cancelled were not loaded.",
        "",
    ]
    path = DOCS / "45-dexter-dms-extract.txt"
    path.write_text("\n".join(lines), encoding="utf-8")
    print("WROTE", path)


def write_batches(deleted):
    ids = [row["docid"] for row in deleted]
    lines = [
        "Dexter deleted and cancelled document ids, batched for the docid field.",
        f"Source: dmsdocumentmaster docstatus in (DELE, CANC), verstatus A,",
        f"publishdate BETWEEN '{DEL_FROM}' AND '{DEL_TO}'.",
        f"Count: {len(ids)}. Batch size: {BATCH}.",
        "Event-log delete and cancel actions are not part of this list.",
        "",
    ]
    for start in range(0, len(ids), BATCH):
        chunk = ids[start : start + BATCH]
        batch_no = start // BATCH + 1
        lines.append(f"Batch {batch_no} ({len(chunk)} document ids)")
        lines.append(", ".join(chunk))
        quoted = ", ".join(f"'{docid}'" for docid in chunk)
        lines.append(f"docid IN ({quoted})")
        lines.append("")
    path = DOCS / "46-dexter-deleted-cancelled-docids.txt"
    path.write_text("\n".join(lines), encoding="utf-8")
    print("WROTE", path)


def write_documents(deleted):
    lines = [
        "Dexter deleted and cancelled document records.",
        f"publishdate BETWEEN '{DEL_FROM}' AND '{DEL_TO}', verstatus A, docstatus DELE or CANC.",
        "Visibility lists and workflow people lists are omitted. Page HTML is included.",
        "This file is not built from dmseventlog.",
        "",
    ]
    for row in deleted:
        lines.append(
            " | ".join(
                [
                    cell(row["docid"]),
                    cell(row["doctitle"]),
                    cell(row["docrefno"]),
                    "v" + cell(row["docversion"]),
                    cell(row["docstatus"]),
                    "ver " + cell(row["verstatus"]),
                    cell(row["doctype"]),
                    "flag " + cell(row["filetypeflag"]),
                    cell(row["filename"]),
                    "parent " + cell(row["parent"]),
                    "published " + cell(row["publishdate"]),
                    "deleted " + cell(row["deldate"]),
                    cell(row["reasonforrev"]),
                ]
            )
        )
        body = cell(row["doccontent"])
        lines.append(body if body else "(no page HTML)")
        lines.append("")
    path = DOCS / "47-dexter-deleted-cancelled-documents.txt"
    path.write_text("\n".join(lines), encoding="utf-8")
    print("WROTE", path, "chars", path.stat().st_size)


def fetch_attachments(cur, deleted):
    grouped = {row["docid"]: [] for row in deleted}
    ids = list(grouped)
    for start in range(0, len(ids), 80):
        chunk = ids[start : start + 80]
        placeholders = ",".join(["%s"] * len(chunk))
        cur.execute(
            f"""
            SELECT docid, filename, uniquefilename, status, filesize, source, ispublished
            FROM dmsfilemanager
            WHERE docid IN ({placeholders})
            ORDER BY docid, id
            """,
            chunk,
        )
        for att in cur.fetchall():
            grouped.setdefault(att["docid"], []).append(att)
    return grouped


def plain_text(value, limit):
    raw = cell(value)
    if not raw:
        return ""
    text = re.sub(r"(?is)<script\b.*?>.*?</script>", " ", raw)
    text = re.sub(r"(?is)<style\b.*?>.*?</style>", " ", text)
    text = re.sub(r"(?i)<br\s*/?>", "\n", text)
    text = re.sub(r"(?i)</p>", "\n", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    text = text.replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) <= limit:
        return text
    cut = text[:limit].rsplit(" ", 1)[0]
    return cut + "..."


def attachment_names(row, attachments):
    names = []
    own = cell(row.get("filename"))
    if own:
        names.append(own)
    listed = []
    for att in attachments:
        name = cell(att.get("filename")) or cell(att.get("uniquefilename"))
        if not name or name in names:
            continue
        listed.append(
            {
                "filename": name,
                "status": cell(att.get("status")),
                "size": cell(att.get("filesize")),
                "source": cell(att.get("source")),
                "published": cell(att.get("ispublished")),
            }
        )
    return own, listed


def write_day_by_day(deleted, attachments):
    documents = []
    for row in deleted:
        own, listed = attachment_names(row, attachments.get(row["docid"], []))
        published = cell(row["publishdate"])
        documents.append(
            {
                "docid": cell(row["docid"]),
                "title": cell(row["doctitle"]),
                "refno": cell(row["docrefno"]),
                "version": cell(row["docversion"]),
                "status": cell(row["docstatus"]),
                "day": published[:10],
                "published": published,
                "documentFile": own,
                "contentText": plain_text(row.get("doccontent"), CONTENT_LIMIT),
                "attachments": listed,
            }
        )

    index_dir = DOCS.parent / "rag"
    index_dir.mkdir(parents=True, exist_ok=True)
    index_path = index_dir / "doc-index.json"
    index_path.write_text(
        json.dumps(
            {
                "generated": datetime.now().strftime("%Y-%m-%d"),
                "from": DEL_FROM,
                "to": DEL_TO,
                "documents": documents,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print("WROTE", index_path, "docs", len(documents))

    lines = [
        "Day-by-day DMS documents for docid search.",
        f"publishdate BETWEEN '{DEL_FROM}' AND '{DEL_TO}', verstatus A, docstatus DELE or CANC.",
        "Grouped by publish date. A docid search summarizes Content and lists Attachments.",
        "This file is not built from dmseventlog.",
        "",
    ]
    day = None
    for doc in documents:
        if doc["day"] != day:
            day = doc["day"]
            count = sum(1 for item in documents if item["day"] == day)
            lines.append(f"Day {day or '(no publish date)'} ({count} documents)")
        lines.append(
            " | ".join(
                [
                    doc["docid"],
                    doc["title"],
                    doc["refno"],
                    "v" + doc["version"],
                    doc["status"],
                ]
            )
        )
        lines.append("Document file: " + (doc["documentFile"] or "(none)"))
        lines.append("Content: " + (plain_text(doc["contentText"], DAY_EXCERPT) or "(no page content)"))
        if doc["attachments"]:
            lines.append("Attachments:")
            for att in doc["attachments"]:
                lines.append(
                    "- "
                    + " | ".join(
                        [
                            att["filename"],
                            "status " + att["status"],
                            "size " + att["size"],
                            "source " + att["source"],
                        ]
                    )
                )
        else:
            lines.append("Attachments: (none)")
        lines.append("")
    path = DOCS / "48-dexter-day-by-day.txt"
    path.write_text("\n".join(lines), encoding="utf-8")
    print("WROTE", path, "chars", path.stat().st_size)


if __name__ == "__main__":
    main()
