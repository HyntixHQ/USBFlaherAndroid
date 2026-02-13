pub struct FlashConfig {
    pub verify: bool,
    pub volume_label: Option<String>,
}

impl Default for FlashConfig {
    fn default() -> Self {
        Self {
            verify: true,
            volume_label: None,
        }
    }
}
