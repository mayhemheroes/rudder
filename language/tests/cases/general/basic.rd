@format=0
@name="Configure NTP"
@description="test"
@category="ncf_techniques"
@version = 0
@parameters=[]

resource Configure_NTP()

Configure_NTP state technique() {
  @component = "Package present"
  package("ntp").present("","","") as package_present_ntp
} 
