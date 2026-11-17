import json,re,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'src/main/java/de/risingsea'
LANG=ROOT/'src/main/resources/assets/risingsea/lang'
class Localizations(unittest.TestCase):
    def test_english_and_german_keys_match(self):
        de=json.loads((LANG/'de_de.json').read_text(encoding='utf-8'))
        en=json.loads((LANG/'en_us.json').read_text(encoding='utf-8'))
        self.assertEqual(set(de),set(en))
        self.assertGreaterEqual(len(de),75)
        for v in (de,en):
            self.assertEqual(v['risingsea.page'].count('%s'),2)
            self.assertEqual(v['risingsea.chat.config'].count('%s'),29)
            self.assertEqual(v['risingsea.chat.status'].count('%s'),7)
    def test_gui_option_keys_all_exist(self):
        gui=(SRC/'client/RisingSeaConfigScreen.java').read_text(encoding='utf-8')
        values=dict(re.findall(r'(?:n|t|action)\("([a-z]+)",\s*"([^"]+)"\)',gui))
        lang=json.loads((LANG/'en_us.json').read_text(encoding='utf-8'))
        for key in values:
            self.assertIn('risingsea.option.'+key,lang)
    def test_hud_is_server_synchronized_and_upwards(self):
        hud=(SRC/'client/RisingSeaClient.java').read_text(encoding='utf-8')
        server=(SRC/'RisingSea.java').read_text(encoding='utf-8')
        self.assertIn('effect.rising',hud)
        self.assertIn('HudElementRegistry.addLast',hud)
        self.assertIn('gameTicks % TICKS_PER_SECOND == 0 && config.showCountdown',server)
        self.assertIn('remainingSeconds',hud)
if __name__=='__main__': unittest.main()
