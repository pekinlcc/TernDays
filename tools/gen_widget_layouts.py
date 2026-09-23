#!/usr/bin/env python3
"""
生成 Android 小组件的 4 份布局:三种外观(素面 / 系统材质 / 品牌渐变)+ 选择器预览。

四份布局的层级必须完全一致,只换底面 drawable、文字配色(预览再填上示例文字)。
手工维护四份几乎一样的 XML 迟早会漂移,所以一律由本脚本生成;CI 会重跑一遍并
`git diff --exit-code`,手改生成物会被拦下。

用法:python3 tools/gen_widget_layouts.py
"""
import os

OUT = os.path.join(os.path.dirname(__file__), '..', 'android', 'app', 'src', 'main', 'res', 'layout')

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  本文件由 tools/gen_widget_layouts.py 生成,请勿手改(CI 会检查)。
  2×2 小组件 · {name}
  年份眉题 + Top 3 城市三行等权重(城市名 15 / 天数 19 / 单位 11);行间与末尾的弹性间距平分余量。
  {note}
-->
'''

ROOT_OPEN = '''<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
{root_id}    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@drawable/{bg}"
    android:padding="16dp">

'''

YEAR = '''    <TextView
{year_id}        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:maxLines="1"
        android:includeFontPadding="false"
        android:fontFamily="sans-serif-medium"
{year_text}        android:textSize="12sp"
        android:textColor="@color/{year}" />

'''

EMPTY = '''    <TextView
        android:id="@+id/widget_empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:includeFontPadding="false"
        android:text="@string/widget_empty"
        android:textSize="13sp"
        android:textColor="@color/{secondary}"
        android:visibility="gone" />

'''

GAP = '''    <FrameLayout
{gap_id}        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:minHeight="{minh}dp"{vis} />

'''

ROW = '''    <LinearLayout
{row_id}        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:baselineAligned="true"{row_vis}>
        <TextView
{city_id}            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:maxLines="1"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:fontFamily="sans-serif-medium"
{city_text}            android:textSize="15sp"
            android:textColor="@color/{primary}" />
        <TextView
{days_id}            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:maxLines="1"
            android:includeFontPadding="false"
            android:fontFamily="sans-serif-medium"
            android:fontFeatureSettings="tnum"
{days_text}            android:textSize="19sp"
            android:textColor="@color/{primary}" />
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="2dp"
            android:maxLines="1"
            android:includeFontPadding="false"
            android:text="@string/widget_unit_day"
            android:textSize="11sp"
            android:textColor="@color/{secondary}" />
    </LinearLayout>

'''

TAIL = '''    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
'''

STYLES = {
    'widget_style_plain.xml': dict(
        name='素面', bg='widget_bg_plain',
        year='widgetAccent', primary='widgetPrimary', secondary='widgetSecondary',
        note='底面:白 / #1C1C1E 实心;唯一品牌色在年份上。'),
    'widget_style_material.xml': dict(
        name='系统材质(Android 近似)', bg='widget_bg_material',
        year='widgetAccent', primary='widgetPrimary', secondary='widgetMaterialSecondary',
        note='底面:高不透明度中性色——小组件是静态快照,做不出实时模糊,这里是近似;次级文字加深以压住任意壁纸。'),
    'widget_style_gradient.xml': dict(
        name='品牌渐变', bg='widget_bg_gradient',
        year='widgetOnBrandYear', primary='widgetOnBrand', secondary='widgetOnBrandFaint',
        note='底面:品牌色竖向渐变,文字白色三级(深浅模式各一组色标)。'),
}

SAMPLE = [('widget_preview_city_1', 'widget_preview_days_1'),
          ('widget_preview_city_2', 'widget_preview_days_2'),
          ('widget_preview_city_3', 'widget_preview_days_3')]


def ident(name, indent):
    return f'{indent}android:id="@+id/{name}"\n'


def text(res, indent):
    return f'{indent}android:text="@string/{res}"\n'


def build(style, preview=False):
    s = HEADER.format(
        name=style['name'] + (' · 选择器预览(示例数据)' if preview else ''),
        note=('预览:与真实布局同一层级,填示例数据,供小组件选择器展示。' if preview else style['note']),
    )
    s += ROOT_OPEN.format(root_id='' if preview else ident('widget_root', '    '), bg=style['bg'])
    s += YEAR.format(
        year_id='' if preview else ident('widget_year', '        '),
        year_text=text('widget_preview_year', '        ') if preview else '',
        year=style['year'],
    )
    if not preview:
        s += EMPTY.format(secondary=style['secondary'])
    for i in (1, 2, 3):
        hidden = not preview and i > 1
        s += GAP.format(
            gap_id=ident(f'widget_gap_{i}', '        ') if (not preview and i > 1) else '',
            minh=8 if i == 1 else 6,
            vis='\n        android:visibility="gone"' if hidden else '',
        )
        city_res, days_res = SAMPLE[i - 1]
        s += ROW.format(
            row_id='' if preview else ident(f'widget_row_{i}', '        '),
            row_vis='' if preview else '\n        android:visibility="gone"',
            city_id='' if preview else ident(f'widget_city_{i}', '            '),
            days_id='' if preview else ident(f'widget_days_{i}', '            '),
            city_text=text(city_res, '            ') if preview else '',
            days_text=text(days_res, '            ') if preview else '',
            primary=style['primary'], secondary=style['secondary'],
        )
    s += TAIL
    return s


def main():
    for fname, style in STYLES.items():
        with open(os.path.join(OUT, fname), 'w', encoding='utf-8') as f:
            f.write(build(style))
    with open(os.path.join(OUT, 'widget_preview.xml'), 'w', encoding='utf-8') as f:
        f.write(build(STYLES['widget_style_plain.xml'], preview=True))
    print('generated 3 style layouts + widget_preview.xml')


if __name__ == '__main__':
    main()
