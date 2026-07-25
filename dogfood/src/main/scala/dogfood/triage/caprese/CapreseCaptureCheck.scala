package dogfood.triage.caprese

import language.experimental.captureChecking

class File:
  def read: String = ""

def use(f: File^): String = f.read
