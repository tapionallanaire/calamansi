# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import re
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from prepare import resource_maps, rewrite_resources, rewrite_value_definitions, rewrite_visible_wording


def check():
    with TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "values").mkdir()
        source = '<resources><style name="Theme.Dolphin" parent="Theme.Material3"/><style name="Theme.Dolphin.Main"/><string name="title">Title</string><attr name="customColor" format="color"/></resources>'
        (root / "values/themes.xml").write_text(source)
        names = resource_maps(root)
        text = rewrite_resources(rewrite_value_definitions(source, names), names)
        values = ET.fromstring(text)
        assert values[0].get("parent") == "Theme.Material3"
        assert values[1].get("name") == "dolphin_Theme.Dolphin.Main"
        assert values[1].get("parent") == "dolphin_Theme.Dolphin"
        assert rewrite_resources('R.style.Theme_Dolphin_Main R.string.title @string/title ?attr/customColor @android:string/ok R.id.title', names) == 'R.style.dolphin_Theme_Dolphin_Main R.string.dolphin_title @string/dolphin_title ?attr/dolphin_customColor @android:string/ok R.id.title'
        framework = {("string", name): "dolphin_" + name for name in ("ok", "yes", "no", "cancel")}
        references = " ".join("android.R.string." + name for name in ("ok", "yes", "no", "cancel"))
        assert rewrite_resources(references, framework) == references
        assert rewrite_resources("R.string.ok org.calamansi.dolphin.R.string.ok", framework) == "R.string.dolphin_ok org.calamansi.dolphin.R.string.dolphin_ok"
    source = (
        '<resources><string name="launch">Dolphin %1$s '
        '<a href="https://Dolphin.example.org/help">Dolphin</a> DolphinBar</string>'
        '<string name="about_copyright">Copyright Dolphin contributors</string></resources>'
    )
    values = ET.fromstring(rewrite_visible_wording(source))
    visible = ''.join(values[0].itertext())
    assert not re.search(r'\bDolphin\b', visible)
    assert '%1$s' in visible and 'DolphinBar' in visible
    assert values[0].find('a').get('href') == 'https://Dolphin.example.org/help'
    assert values[1].text == 'Copyright Dolphin contributors'
    print("Dolphin resource namespacing check passed")


if __name__ == "__main__":
    check()
