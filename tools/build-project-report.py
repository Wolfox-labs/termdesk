"""Export the project specification with embedded CJK fonts and actual screenshots.

Requires reportlab and Pillow. Run from any directory; --font supports other hosts.
"""
from __future__ import annotations

import argparse
import html
import os
from pathlib import Path
import re

from PIL import Image as PILImage
from reportlab import rl_config
from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, Image, KeepTogether, PageBreak, PageTemplate,
    Paragraph, Spacer, Table, TableStyle,
)

ROOT = Path(__file__).resolve().parents[1]
INK = colors.HexColor('#172B3A')
ACCENT = colors.HexColor('#176B73')
MUTED = colors.HexColor('#546774')
PALE = colors.HexColor('#EFF5F6')
WIDTH = A4[0] - 38 * mm
rl_config.warnOnMissingFontGlyphs = 1


def inline(value: str) -> str:
    value = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1', value)
    value = html.escape(value)
    value = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', value)
    value = re.sub(r'`([^`]+)`', r'<font color="#176B73">\1</font>', value)
    return value


def register_fonts(font: Path, bold: Path | None):
    pdfmetrics.registerFont(TTFont('Report', str(font), subfontIndex=0))
    pdfmetrics.registerFont(TTFont('ReportBold', str(bold or font), subfontIndex=0))
    pdfmetrics.registerFontFamily('Report', normal='Report', bold='ReportBold', italic='Report', boldItalic='ReportBold')


def styles():
    common = dict(fontName='Report', textColor=INK, wordWrap='CJK')
    return {
        'body': ParagraphStyle('Body', fontSize=10, leading=16, spaceAfter=8, **common),
        'h1': ParagraphStyle('Chapter', fontSize=20, leading=28, spaceAfter=15, keepWithNext=True, **common),
        'h2': ParagraphStyle('Section', fontSize=12, leading=18, spaceBefore=10, spaceAfter=7, keepWithNext=True, **common),
        'cell': ParagraphStyle('Cell', fontSize=8.7, leading=13.3, **common),
        'headcell': ParagraphStyle('HeadCell', fontSize=9, leading=14, fontName='ReportBold', textColor=colors.white, wordWrap='CJK'),
        'caption': ParagraphStyle('Caption', fontSize=8.3, leading=12.5, spaceBefore=6, spaceAfter=8, textColor=MUTED, fontName='Report', wordWrap='CJK'),
        'code': ParagraphStyle('Code', fontName='Report', fontSize=8.1, leading=12.8, textColor=INK, backColor=PALE, borderPadding=9, spaceAfter=10, wordWrap='CJK'),
    }


def architecture():
    d = Drawing(WIDTH, 242)
    def box(x, y, width, title, sub):
        d.add(Rect(x, y, width, 47, rx=5, ry=5, fillColor=PALE, strokeColor=colors.HexColor('#C5DADC')))
        d.add(String(x + width/2, y+28, title, fontName='ReportBold', fontSize=10, fillColor=INK, textAnchor='middle'))
        d.add(String(x + width/2, y+12, sub, fontName='Report', fontSize=8, fillColor=MUTED, textAnchor='middle'))
    def arrow(x1, y1, x2, y2):
        d.add(Line(x1, y1, x2, y2, strokeColor=ACCENT, strokeWidth=1))
        if x1 == x2:
            sign = 1 if y2 > y1 else -1
            p = [x2, y2, x2-3, y2-sign*6, x2+3, y2-sign*6]
        else:
            sign = 1 if x2 > x1 else -1
            p = [x2, y2, x2-sign*6, y2-3, x2-sign*6, y2+3]
        d.add(Polygon(p, fillColor=ACCENT, strokeColor=ACCENT))
    box(0, 188, 154, 'Android 工作台', '输入、审批、文件与终端')
    box(190, 188, 154, '电脑管理控制台', '设备、助手、会话观察')
    box(0, 101, 154, '手机沙盒 Agent', '同一 Node 源码与协议')
    box(190, 101, 154, '电脑 Agent', '认证、编排与宿主工具')
    box(0, 12, 154, '助手协议适配', 'app-server / SDK / ACP / CLI')
    box(190, 12, 154, '原生助手与模型服务', '模型连接由助手配置')
    arrow(77, 188, 77, 148)
    arrow(154, 206, 267, 148)
    arrow(267, 188, 267, 148)
    arrow(77, 101, 77, 59)
    arrow(267, 101, 154, 59)
    arrow(154, 35, 190, 35)
    d.add(String(358, 202, '可选 Relay', fontName='ReportBold', fontSize=9, fillColor=ACCENT))
    d.add(String(358, 185, '配对与反向通道', fontName='Report', fontSize=8, fillColor=MUTED))
    d.add(String(358, 169, '不执行工作命令', fontName='Report', fontSize=8, fillColor=MUTED))
    d.scale(0.85, 0.85)
    d.height *= 0.85
    return d


class Report(BaseDocTemplate):
    def __init__(self, filename, version, report_date, **kwargs):
        super().__init__(str(filename), pagesize=A4, leftMargin=19*mm, rightMargin=19*mm,
                         topMargin=20*mm, bottomMargin=19*mm, title='TermDesk 项目说明书',
                         author='TermDesk 项目组', **kwargs)
        frame = Frame(self.leftMargin, self.bottomMargin, self.width, self.height, leftPadding=0,
                      rightPadding=0, topPadding=0, bottomPadding=0)
        self.addPageTemplates(PageTemplate(id='report', frames=frame, onPage=self.decorate))
        self.chapters = []
        self.version = version
        self.report_date = report_date

    def decorate(self, canvas, doc):
        canvas.saveState()
        if doc.page > 1:
            canvas.setFont('Report', 8)
            canvas.setFillColor(MUTED)
            canvas.drawString(19*mm, A4[1]-12*mm, 'TERMDESK  /  项目说明书')
        canvas.setFont('Report', 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(19*mm, 10*mm, f'{self.version}  ·  {self.report_date}')
        canvas.drawRightString(A4[0]-19*mm, 10*mm, str(doc.page))
        canvas.restoreState()

    def afterFlowable(self, flowable):
        if isinstance(flowable, Paragraph) and flowable.style.name == 'Chapter':
            title = flowable.getPlainText()
            key = f'chapter-{len(self.chapters)}'
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(title, key, level=0, closed=False)
            self.chapters.append((title, self.page))


def make_table(rows, style):
    count = len(rows[0])
    ratios = {2: [0.25, 0.75], 3: [0.18, 0.45, 0.37], 4: [0.15, 0.19, 0.33, 0.33]}
    widths = [WIDTH * value for value in ratios.get(count, [1/count]*count)]
    cells = [[Paragraph(inline(cell), style['headcell' if r == 0 else 'cell']) for cell in row] for r, row in enumerate(rows)]
    table = Table(cells, colWidths=widths, hAlign='LEFT', repeatRows=1)
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), ACCENT),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, PALE]),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
        ('LINEBELOW', (0, -1), (-1, -1), 0.5, colors.HexColor('#C5DADC')),
    ]))
    return [table, Spacer(1, 9)]


def figure_group(items, base, style):
    phone = len(items) == 2 and all('phone-' in target for _, target in items)
    width = (WIDTH - 14*mm)/2 if phone else WIDTH
    images, captions = [], []
    for caption, relative in items:
        target = base / relative
        with PILImage.open(target) as im:
            w, h = im.size
        maximum_height = 165*mm if phone else 120*mm
        scale = min(width/w, maximum_height/h)
        images.append(Image(str(target), width=w*scale, height=h*scale, hAlign='CENTER'))
        captions.append(Paragraph(inline(caption), style['caption']))
    if phone:
        table = Table([[images[0], '', images[1]], [captions[0], '', captions[1]]], colWidths=[width, 14*mm, width])
        table.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0), ('RIGHTPADDING', (0, 0), (-1, -1), 0)]))
        return KeepTogether([Spacer(1, 8), table, Spacer(1, 5)])
    return KeepTogether([Spacer(1, 8), images[0], captions[0]])


def export(source: Path, destination: Path, style, version: str, report_date: str):
    lines = source.read_text(encoding='utf-8').splitlines()
    chapters = [line[3:] for line in lines if line.startswith('## ')]
    story = [Spacer(1, 28*mm)]
    story.append(Paragraph('TERMDESK', ParagraphStyle('Brand', fontName='ReportBold', fontSize=35, leading=43, textColor=ACCENT)))
    story.append(Paragraph('项目说明书', ParagraphStyle('Title', fontName='ReportBold', fontSize=28, leading=38, textColor=INK, spaceBefore=12, spaceAfter=15)))
    story.append(Paragraph('手机工作入口 · 双执行位置 · 电脑管理控制台', style['h2']))
    story.append(Paragraph(f'开发版本 {inline(version)}<br/>{inline(report_date)}', style['body']))
    story.append(Spacer(1, 15*mm))
    story.append(Paragraph('实现、边界与真实设备证据', style['h2']))
    count = sum(line.startswith('![') for line in lines)
    story.append(Paragraph(f'以当前源码和实际运行结果说明功能、架构、技术选型与关键业务流程。正文包含 {count} 张页面截图，录屏单独交付；已验证与未验证的能力分别说明。', style['body']))
    story.append(Spacer(1, 12*mm))
    story.append(Paragraph('阅读导引', style['h2']))
    story.append(Paragraph('　 /　'.join(inline(c) for c in chapters), ParagraphStyle('Guide', parent=style['body'], fontSize=9, leading=17)))
    story.append(Paragraph('原始构想文档《移动端agent开发》保留原文。本文区分已实现、已验证与待完成，不将固定回复演示当作真实模型认证。', style['caption']))
    i = next(i for i, line in enumerate(lines) if line.startswith('## '))
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if line.startswith('## '):
            story += [PageBreak(), Paragraph(inline(line[3:]), style['h1'])]
            i += 1
        elif line.startswith('### '):
            story.append(Paragraph(inline(line[4:]), style['h2']))
            i += 1
        elif line.startswith('```'):
            kind = line[3:]
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith('```'):
                block.append(lines[i])
                i += 1
            if kind == 'mermaid':
                story += [architecture(), Spacer(1, 12)]
            else:
                story.append(Paragraph('<br/>'.join(html.escape(v).replace(' ', '&nbsp;') for v in block), style['code']))
            i += 1
        elif line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                row = [cell.strip() for cell in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-+:?', cell) for cell in row):
                    rows.append(row)
                i += 1
            story += make_table(rows, style)
        elif line.startswith('!['):
            match = re.fullmatch(r'!\[(.*?)\]\((.*?)\)', line)
            if not match:
                raise ValueError(f'Invalid figure: {line}')
            items = [match.groups()]
            i += 1
            if 'phone-' in items[0][1]:
                j = i
                while j < len(lines) and not lines[j].strip():
                    j += 1
                second = re.fullmatch(r'!\[(.*?)\]\((.*?)\)', lines[j].strip()) if j < len(lines) else None
                if second and 'phone-' in second.group(2):
                    items.append(second.groups())
                    i = j + 1
            story.append(figure_group(items, source.parent, style))
        else:
            story.append(Paragraph(inline(line), style['body']))
            i += 1
    destination.parent.mkdir(parents=True, exist_ok=True)
    doc = Report(destination, version, report_date)
    doc.build(story)
    print(f'Created {destination} ({doc.page} pages)')
    for title, page in doc.chapters:
        print(f'{page:2d}  {title}')


if __name__ == '__main__':
    fonts = Path(os.environ.get('WINDIR', os.environ.get('SystemRoot', '/usr/share/fonts')))/'Fonts'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT/'documents/项目说明书.md')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--version', help='Defaults to the version declared in the source document')
    parser.add_argument('--date', help='YYYY-MM-DD; defaults to the date declared in the source document')
    parser.add_argument('--font', type=Path, default=fonts/'msyh.ttc')
    parser.add_argument('--bold-font', type=Path)
    config = parser.parse_args()
    source_text = config.source.read_text(encoding='utf-8')
    version_match = re.search(r'版本：([^；\s]+)', source_text)
    date_match = re.search(r'日期：(\d{4}-\d{2}-\d{2})', source_text)
    version = config.version or (version_match.group(1) if version_match else None)
    report_date = config.date or (date_match.group(1) if date_match else None)
    if not version or not report_date or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', report_date):
        parser.error('Declare version/date in the source or provide --version and --date YYYY-MM-DD')
    destination = config.output or ROOT/f'output/pdf/TermDesk-项目说明书-{report_date}.pdf'
    bold = config.bold_font or config.font.with_name('msyhbd.ttc')
    register_fonts(config.font, bold if bold.exists() else None)
    export(config.source.resolve(), destination.resolve(), styles(), version, report_date)
