import unittest
from pathlib import Path
S=(Path(__file__).resolve().parents[1]/"src/main/java/de/risingsea/RisingSea.java").read_text()
class WaveRegression(unittest.TestCase):
    def test_wave_has_no_transient_water_sources(self):
        a=S.index("private int renderWave(")
        b=S.index("void cleanupOnly(",a)
        wave=S[a:b]
        self.assertNotIn("setBlock(",wave)
        self.assertIn("ParticleTypes.SPLASH",wave)
        self.assertIn("ParticleTypes.FALLING_WATER",wave)
    def test_chunks_ahead_of_wave_deferred(self):
        self.assertIn("Math.hypot(dxWave, dzWave) > traveled - cfg.waveWidthBlocks",S)
        self.assertIn("int scanTicks = save.waveActive",S)
    def test_permanent_sea_target_persists(self):
        self.assertIn("if (config.permanentSeaLevelRise) save.level = save.waveDestinationLevel",S)
if __name__=="__main__": unittest.main()
