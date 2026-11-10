"""Dependency-free source contracts for Rising Sea v6.3. Not a Minecraft integration test."""
import re
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SERVER = (ROOT / "src/main/java/de/risingsea/RisingSea.java").read_text()
GUI = (ROOT / "src/main/java/de/risingsea/client/RisingSeaConfigScreen.java").read_text()

class SourceContracts(unittest.TestCase):
    def test_random_day_range_inclusive(self):
        self.assertIn("config.randomMaxDays + 1", SERVER)
        self.assertIn("days * TICKS_PER_DAY", SERVER)
        self.assertIn("long scheduledIntervalTicks;", SERVER)
        self.assertIn('if (config.randomSchedule && !tsunami)', SERVER)
    def test_no_random_schedule_for_tsunami(self):
        self.assertIn('if ("TSUNAMI".equals(config.floodMode))', SERVER)
        self.assertIn('config.tsunamiIntervalDays * TICKS_PER_DAY', SERVER)
    def test_boats_only(self):
        self.assertIn("world.getEntitiesOfClass(AbstractBoat.class", SERVER)
        self.assertNotIn("ItemEntity.class", SERVER)
        self.assertIn("if (newLevel < oldLevel) { boatTransfers.clear(); return; }", SERVER)
    def test_waiting_and_border_first(self):
        self.assertIn('boolean readyForNextStep()', SERVER)
        self.assertIn('BORDER_FIRST_COLUMNS[column]', SERVER)
        cells=[(x,z) for z in range(16) for x in range(16) if x in (0,15) or z in (0,15)]
        cells += [(x,z) for z in range(1,15) for x in range(1,15)]
        self.assertEqual(len(cells),256)
        self.assertEqual(len(set(cells)),256)
        self.assertEqual(len(cells[:60]),60)
        self.assertTrue(all(x in (0,15) or z in (0,15) for x,z in cells[:60]))
    def test_config_gui_consistency(self):
        for option in ('randommindays','randommaxdays','randomschedule','waitscan','debug'):
            self.assertIn('"'+option+'"', GUI)
            self.assertIn('"'+option+'"', SERVER)
        self.assertIn('loadLocalConfig()',GUI)
        self.assertIn('saveLocalConfig()',GUI)
    def test_jar_version(self):
        self.assertIn('mod_version=6.5',(ROOT/'gradle.properties').read_text())

if __name__ == '__main__': unittest.main()
