package uk.gegc.quizmaker.features.document.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"parent", "children", "document"})
@Entity
@Table(name = "document_nodes")
public class DocumentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DocumentNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal ASC")
    @BatchSize(size = 64)
    private List<DocumentNode> children = new ArrayList<>();

    @Min(0)
    @Column(nullable = false)
    private Integer level; // 0=doc, 1=part, 2=chapter, 3=section, 4=subsection, 5=paragraph

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeType type;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Min(0)
    @Column(nullable = false)
    private Integer startOffset;

    @Min(0)
    @Column(nullable = false)
    private Integer endOffset;

    @Column(columnDefinition = "TEXT")
    private String startAnchor;

    @Column(columnDefinition = "TEXT")
    private String endAnchor;

    @Min(0)
    @Column(nullable = false)
    private Integer ordinal;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Strategy strategy;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @NotBlank
    @Column(length = 64, nullable = false)
    private String sourceVersionHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public enum NodeType {
        DOCUMENT,
        PART,
        CHAPTER,
        SECTION,
        SUBSECTION,
        PARAGRAPH,
        OTHER
    }

    public enum Strategy {
        REGEX,
        AI,
        HYBRID
    }

    // Helper methods for tree operations
    public void addChild(DocumentNode child) {
        children.add(child);
        child.setParent(this);
    }

    public void removeChild(DocumentNode child) {
        children.remove(child);
        child.setParent(null);
    }

    public boolean isRoot() {
        return parent == null;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public int getDepth() {
        return level;
    }
}
