# generated by rudderc
# Generated from json technique
@format = 0
@name = "simplest"
@description = "rudderlang simplest for a complete loop"
@version = "1.0"
@category = "ncf_techniques"
@parameters = []

resource technique_simplest()

technique_simplest state technique() {
    @component = "File absent"
  @id = "aeca6c93-47af-41ee-ba4a-8772f4ce7dd8"
  file("""tmp""").absent() as file_absent_tmp
}
