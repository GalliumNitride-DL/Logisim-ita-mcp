# Problems I found when Agents are using the MCP

- Many agents spend a lot of time determining whether to use tunnels or to connect directly. We need to tell them which is better in which situation.
- Many agents are confused about the pin layout / supported bit width of the builtin components. They often spend a lot of time guessing the positioning of the pins and criticize themselves without trying to search for them with the relevant tools.
- Agents often place tunnels / ground / power in wrong / unconventional facing. This greatly affects circuit readability.
- In interactive mode when agents modify circuits without opening them (bring them to the UI), the selection highlight will display erroneous information.