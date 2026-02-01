val loader_version = property("loader_version") as String
val fabric_version = property("fabric_version") as String

dependencies {
    implementation(project(path = ":core", configuration = "namedElements"))

    modImplementation("net.fabricmc:fabric-loader:${loader_version}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${fabric_version}")
}
