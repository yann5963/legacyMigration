sed -i '/import lombok.ToString;/a import com.fasterxml.jackson.annotation.JsonIgnore;' src/main/java/com/migration/model/FileChunkStatus.java
sed -i '/@ToString.Exclude/a \    @JsonIgnore' src/main/java/com/migration/model/FileChunkStatus.java
