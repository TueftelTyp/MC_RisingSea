import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SERVER = (ROOT / 'src/main/java/de/risingsea/RisingSea.java').read_text()
CLIENT = (ROOT / 'src/main/java/de/risingsea/client/RisingSeaClient.java').read_text()

class CountdownRegression(unittest.TestCase):
    def test_manual_tsunami_not_a_massive_timer(self):
        self.assertIn('config.tsunamiIntervalDays == 0 ? -1L', SERVER)
        self.assertIn('scheduleTicks < 0 ? -1L', SERVER)
        self.assertIn('if (interval > 0)', SERVER)
        self.assertIn('seconds > 0', CLIENT)
    def test_only_tile_not_actionbar_countdown(self):
        self.assertNotIn('sendOverlayMessage(Component.translatable("risingsea.chat.overlay"', SERVER)
        self.assertIn('drawVanillaEffectCard', CLIENT)
    def test_warning_uses_configured_window(self):
        self.assertIn('json.get("countdownLastSeconds").getAsInt()', CLIENT)
        self.assertIn('left > warningSeconds', CLIENT)
        self.assertIn('!config.waitForChunkPass || worker.readyForNextStep()', SERVER)
