package com.example.api

enum class CaInterSubject(
    val paperNumber: Int,
    val subjectName: String,
    val subTopics: List<String>
) {
    ADV_ACCOUNTING(
        1, 
        "Paper 1: Advanced Accounting", 
        listOf("Partnership Accounts", "Consolidated Financial Statements", "Buyback of Securities", "Amalgamation")
    ),
    CORP_LAWS(
        2, 
        "Paper 2: Corporate and Other Laws", 
        listOf("Prospectus and Allotment", "Share Capital and Debentures", "Management and Administration", "Foreign Companies")
    ),
    TAXATION(
        3, 
        "Paper 3: Taxation", 
        listOf("Capital Gains", "GST", "Salary Income", "House Property")
    ),
    COST_MGMT(
        4, 
        "Paper 4: Cost and Management Accounting", 
        listOf("Material Cost", "Labor Cost", "Overheads", "Standard Costing")
    ),
    AUDIT_ETHICS(
        5, 
        "Paper 5: Auditing and Ethics", 
        listOf("SA 240", "Company Audit", "Audit Documentation", "Internal Control")
    ),
    FIN_STRAT_MGMT(
        6, 
        "Paper 6: Financial Management and Strategic Management", 
        listOf("Cost of Capital", "Leverages", "Strategic Analysis", "Business Level Decisions")
    );

    companion object {
        fun fromTag(tag: String?): CaInterSubject? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.lowercase().trim()
            
            // Check direct paper number/name matching
            for (value in values()) {
                val cleanPaperName = value.subjectName.substringAfter(": ").lowercase().trim()
                if (normalized == "paper ${value.paperNumber}" ||
                    normalized == "paper${value.paperNumber}" ||
                    normalized == value.name.lowercase() ||
                    normalized.contains(cleanPaperName) ||
                    value.subjectName.lowercase().contains(normalized)
                ) {
                    return value
                }
            }
            return null
        }
    }
}

data class SubjectMasteryStats(
    val subjectName: String,
    val totalFocusMs: Long
)
